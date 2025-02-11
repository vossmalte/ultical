package de.ultical.backend.api;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.ultical.backend.api.transferClasses.DfvMvName;
import de.ultical.backend.api.transferClasses.DfvMvPlayer;
import de.ultical.backend.app.Authenticator;
import de.ultical.backend.app.UltiCalConfig;
import de.ultical.backend.data.DataStore;
import de.ultical.backend.data.DataStore.DataStoreCloseable;
import de.ultical.backend.data.policies.Policy;
import de.ultical.backend.model.Club;
import de.ultical.backend.model.DfvPlayer;
import de.ultical.backend.model.DivisionAge;
import de.ultical.backend.model.DivisionType;
import de.ultical.backend.model.Gender;
import de.ultical.backend.model.Player;
import de.ultical.backend.model.Roster;
import de.ultical.backend.model.RosterPlayer;
import de.ultical.backend.model.User;
import io.dropwizard.auth.Auth;

@Path("/roster")
public class RosterResource {

    private static final String DB_ACCESS_FAILED = "Database access failed!";

    private final static Logger LOGGER = LoggerFactory.getLogger(RosterResource.class);

    @Inject
    Client client;

    @Inject
    DataStore dataStore;

    @Inject
    UltiCalConfig config;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Roster addRoster(@Auth @NotNull final User currentUser, @NotNull final Roster newRoster) {
        if (this.dataStore == null) {
            throw new WebApplicationException("Dependency Injectino for data store failed!",
                    Status.INTERNAL_SERVER_ERROR);
        }

        try (DataStoreCloseable c = this.dataStore.getClosable()) {

            this.validateRoster(newRoster, currentUser);
            try {
                this.dataStore.addNew(newRoster);
                newRoster.setVersion(1);
	    
                return newRoster;
            } catch (PersistenceException pe) {
                LOGGER.error(DB_ACCESS_FAILED, pe);
                throw new WebApplicationException(DB_ACCESS_FAILED, Status.INTERNAL_SERVER_ERROR);
            }
        }
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Roster updateRoster(@Auth @NotNull User currentUser, @NotNull Roster updatedRoster) {
        if (this.dataStore == null) {
            throw new WebApplicationException("Dependency Injection for data store failed!",
                    Status.INTERNAL_SERVER_ERROR);
        }

        try (DataStoreCloseable c = this.dataStore.getClosable()) {

            this.validateRoster(updatedRoster, currentUser);

            try {
                this.dataStore.update(updatedRoster);
            } catch (PersistenceException pe) {
                LOGGER.error(DB_ACCESS_FAILED, pe);
                throw new WebApplicationException(DB_ACCESS_FAILED, Status.INTERNAL_SERVER_ERROR);
            }

            updatedRoster.setVersion(updatedRoster.getVersion() + 1);

            return updatedRoster;
        }
    }

    private void validateRoster(Roster roster, User currentUser) {
        Authenticator.assureTeamAdmin(this.dataStore, roster.getTeam().getId(), currentUser);

        // check if roster for this season already exists for this team
        Roster result = this.dataStore.getRosterOfTeamSeason(roster);

        // check if the found entry is the one updated (or newly created which
        // always results in 'true' of the expression
        if (result != null && result.getId() != roster.getId()) {
            // this roster is already present for this team
            throw new WebApplicationException("e101 - Roster already exists for team", Status.CONFLICT);
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{rosterId}")
    public Player addPlayerToRoster(@Auth @NotNull User currentUser, @PathParam("rosterId") Integer rosterId,
            @NotNull DfvMvName dfvMvName) {
        if (this.dataStore == null) {
            throw new WebApplicationException("Dependency Injection for data store failed!",
                    Status.INTERNAL_SERVER_ERROR);
        }

        try (DataStoreCloseable c = this.dataStore.getClosable()) {

            // Validation
             dfvMvName = this.dataStore.getDfvMvName(dfvMvName.getDfvNumber());

            if (!dfvMvName.isDse()) {
                throw new WebApplicationException("User did not agree to publish his data on the web (no DSE present)",
                        Status.FORBIDDEN);
            }

            Roster roster = this.dataStore.get(rosterId, Roster.class);
            if (roster == null) {
                throw new WebApplicationException(String.format("Roster with ID=%d does not exist!", rosterId),
                        Status.NOT_FOUND);
            }
            Authenticator.assureTeamAdmin(this.dataStore, roster.getTeam().getId(), currentUser);

            // get player if exists
            DfvPlayer player = this.dataStore.getPlayerByDfvNumber(dfvMvName.getDfvNumber());

            Policy policy = Policy.getPolicy(roster.getContext(), this.dataStore);
            Policy.Eligibility eligibility = null;

            if (player == null) {
                // a new player

                // get full player data from dfv-mv
                WebTarget target = this.client.target(this.config.getDfvApi().getUrl()).path("profil")
                        .path(String.valueOf(dfvMvName.getDfvNumber()))
                        .queryParam("token", this.config.getDfvApi().getToken())
                        .queryParam("secret", this.config.getDfvApi().getSecret());

                Invocation.Builder invocationBuilder = target.request(MediaType.APPLICATION_JSON);
                DfvMvPlayer dfvMvPlayer = invocationBuilder.get(DfvMvPlayer.class);

                // create and persist player object
                DfvPlayer dfvPlayer = new DfvPlayer(dfvMvPlayer);
                dfvPlayer.setFirstName(dfvMvName.getFirstName());
                dfvPlayer.setLastName(dfvMvName.getLastName());
                dfvPlayer.setEmail(dfvMvPlayer.getEmail());
                dfvPlayer.setLastModified(dfvMvName.getLastModified());
                dfvPlayer.setPaid(dfvMvPlayer.isPaid());
                eligibility = policy.getPlayerEligibility(dfvMvPlayer);
                if (eligibility != Policy.Eligibility.ELIGIBLE) {
                 	dfvPlayer.setEligibleUntil(dfvMvName.getLastModified());
                }
                dfvPlayer.setClub(dfvMvName.getClub());

                player = dfvPlayer;

                this.dataStore.storeDfvPlayer(dfvPlayer);
            }

            /*
             * check if found player is an active or passive player. If the
             * player is passive, an addition to an roster is not allowed.
             */
            if (!player.isEligible()) {
                if (eligibility == null)
                throw new WebApplicationException(
                        "e104 - Player is not eligible to participate in tournaments. She is either registered as a passive player, doesn't have her DSE signed or the yearly fees for her have not been sent by her club.",
                        Status.EXPECTATION_FAILED);
                switch (eligibility) {
                    case NOT_PAID:
                        throw new WebApplicationException(
                                "e105 - Player is not eligible to participate in tournaments. The yearly fees for her have not been paid by her club.",
                                Status.EXPECTATION_FAILED);
                    case NO_DSE:
                        throw new WebApplicationException(
                                "e106 - Player is not eligible to participate in tournaments. She doesn't have her DSE signed.",
                                Status.EXPECTATION_FAILED);
                    case NOT_ACTIVE:
                        throw new WebApplicationException(
                                "e107 - Player is not eligible to participate in tournaments. She is registered as a passive player.",
                                Status.EXPECTATION_FAILED);
                    case IDLE:
                        throw new WebApplicationException(
                                "e108 - Player is not eligible to participate in tournaments. She is registered as an idle player.",
                                Status.EXPECTATION_FAILED);
                }
            }

            this.checkPlayerEligibility(roster, player);

            // do policy check if context exists
            if (roster.getContext() != null) {
                switch (policy.addPlayerToRoster(player, roster)) {
                case Policy.ALREADY_IN_DIFFERENT_ROSTER:
                    String differentTeamName = "";
                    if (policy.getErrorParameters().containsKey("team_name")) {
                        differentTeamName = policy.getErrorParameters().get("team_name");
                    }
                    throw new WebApplicationException(
                            "e101-" + differentTeamName
                                    + "- Player is already in a different roster of this season and division",
                            Status.CONFLICT);
                }
            }

            // add player to roster
            this.dataStore.addPlayerToRoster(roster, player);

            return player;
        }
    }

    /**
     * throws an exception if either the player's gender does not match with the
     * division's requirements or if the player is too old or too young for the
     * respective division. In case the player is eligible to player in the
     * division determined by the roster, this method silently returns.
     *
     * @param roster
     * @param player
     */
    private void checkPlayerEligibility(Roster roster, DfvPlayer player) {
        // check if gender matches with divison
        boolean wrongGender = false;
        if (Gender.MALE.equals(player.getGender())) {
            if (DivisionType.WOMEN.equals(roster.getDivisionType())) {
                wrongGender = true;
            }
        } else if (Gender.NA.equals(player.getGender())) {
            if (DivisionType.WOMEN.equals(roster.getDivisionType())) {
                wrongGender = true;
            }
        }
        if (wrongGender) {
            throw new WebApplicationException("e102-Player has wrong gender for this Division", Status.CONFLICT);
        }

        // check player's age
        boolean wrongAge = false;
        if (roster.getDivisionAge() != DivisionAge.REGULAR) {
            final LocalDate birthDate = player.getBirthDate();
            if (birthDate == null) {
                throw new WebApplicationException("A player, registered at the dfv, should have a valid birthdate",
                        Status.CONFLICT);
            }
            int age = roster.getSeason().getYear() - birthDate.getYear();

            if (roster.getDivisionAge() == DivisionAge.MASTERS && player.getGender() == Gender.FEMALE) {
                // women masters can be 3 years younger than their male
                // counterparts
                age += 3;
            } else if (roster.getDivisionAge() == DivisionAge.U17 && player.getGender() == Gender.FEMALE) {
                // women are allowed to play U17 one year longer then male
                // players.
                // i.e. 18yo women are allowed to play U17
                age -= 1;
            }
            wrongAge = (roster.getDivisionAge().isHasToBeOlder() && age < roster.getDivisionAge().getAgeDifference())
                    || (!roster.getDivisionAge().isHasToBeOlder() && age > roster.getDivisionAge().getAgeDifference());
        }
        if (wrongAge) {
            throw new WebApplicationException("e103-Player's age does not match division's regulations",
                    Status.CONFLICT);
        }
    }

    @DELETE
    @Path("{rosterId}/player/{playerId}")
    public void deletePlayerFromRoster(@Auth @NotNull User currentUser, @PathParam("rosterId") int rosterId,
            @PathParam("playerId") int playerId) {
        if (this.dataStore == null) {
            throw new WebApplicationException();
        }
        try (DataStoreCloseable c = this.dataStore.getClosable()) {
            Roster roster = this.dataStore.get(rosterId, Roster.class);

            Authenticator.assureTeamAdmin(this.dataStore, roster.getTeam().getId(), currentUser);

            try {
                // get list of start-dates of official tournaments of this
                // division and season that the team of the roster attends
                List<LocalDate> blockingDates = this.dataStore.getRosterBlockingDates(rosterId);

                RosterPlayer rosterPlayerToDelete = null;
                for (RosterPlayer rosterPlayer : roster.getPlayers()) {
                    if (rosterPlayer.getPlayer().getId() == playerId) {
                        rosterPlayerToDelete = rosterPlayer;
                    }
                }

                if (rosterPlayerToDelete == null) {
                    throw new WebApplicationException("Player not found in Roster", Status.NOT_FOUND);
                }

                LocalDate today = LocalDate.now();

                boolean playerBlocked = false;
                for (LocalDate blockingDate : blockingDates) {
                    // take all blocking dates including today
                    if (!blockingDate.isAfter(today)) {
                        // if the blocking date is after the day the player was
                        // added, he is blocked
                        if (blockingDate.isAfter(rosterPlayerToDelete.getDateAdded())) {
                            playerBlocked = true;
                        }
                    }
                }

                if (playerBlocked) {
                    throw new WebApplicationException(
                            "Player cannot be deleted, because an official tournament has taken place in this division and season with this team attending",
                            Status.FORBIDDEN);
                }

                this.dataStore.removePlayerFromRoster(playerId, rosterId);
            } catch (PersistenceException pe) {
                LOGGER.error(DB_ACCESS_FAILED, pe);
                throw new WebApplicationException(DB_ACCESS_FAILED);
            }
        }
    }

    @DELETE
    @Path("{rosterId}")
    public void deleteRoster(@Auth @NotNull User currentUser, @PathParam("rosterId") int rosterId) {
        if (this.dataStore == null) {
            throw new WebApplicationException();
        }
        try (DataStoreCloseable c = this.dataStore.getClosable()) {
            Roster rosterToDelete = this.dataStore.get(rosterId, Roster.class);

            Authenticator.assureTeamAdmin(this.dataStore, rosterToDelete.getTeam().getId(), currentUser);

            // roster cannot be deleted if registered for an event
            if (!this.dataStore.getTeamRegistrationsByRosters(Collections.singletonList(rosterToDelete)).isEmpty()) {
                throw new WebApplicationException("Roster cannot be deleted because it is registered for a tournament.",
                        Status.FORBIDDEN);
            }

            this.dataStore.remove(rosterId, Roster.class);

        } catch (PersistenceException pe) {
	    LOGGER.error(DB_ACCESS_FAILED, pe);
            throw new WebApplicationException("Accessing the database failed!", Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("{rosterId}/blocking")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LocalDate> getBlockingDates(@Auth @NotNull User currentUser, @PathParam("rosterId") int rosterId) {
        if (this.dataStore == null) {
            throw new WebApplicationException();
        }
        try (DataStoreCloseable c = this.dataStore.getClosable()) {
            Roster rosterToEdit = this.dataStore.get(rosterId, Roster.class);

            Authenticator.assureTeamAdmin(this.dataStore, rosterToEdit.getTeam().getId(), currentUser);

            try {
                return this.dataStore.getRosterBlockingDates(rosterId);
            } catch (PersistenceException pe) {
		LOGGER.error(DB_ACCESS_FAILED, pe);
                throw new WebApplicationException("Accessing the database failes!");
            }
        }
    }
}

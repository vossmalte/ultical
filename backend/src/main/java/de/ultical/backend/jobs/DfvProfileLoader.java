package de.ultical.backend.jobs;

import de.ultical.backend.api.transferClasses.DfvMvName;
import de.ultical.backend.api.transferClasses.DfvMvPlayer;
import de.ultical.backend.app.MailClient;
import de.ultical.backend.app.UltiCalConfig;
import de.ultical.backend.app.mail.SystemMessage;
import de.ultical.backend.data.DataStore;
import de.ultical.backend.data.policies.Policy;
import de.ultical.backend.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DfvProfileLoader {

    private final static Logger LOGGER = LoggerFactory.getLogger(DfvProfileLoader.class);

    @Inject
    Client client;

    @Inject
    UltiCalConfig config;

    @Inject
    DataStore dataStore;

    @Inject
    MailClient mailClient;

    public boolean getDfvMvNames() {

        if (!this.config.getJobsConf().isDfvMvSyncEnabled()) {
            return false;
        }

        try (DataStore.DataStoreCloseable c = this.dataStore.getClosable()) {

            WebTarget target = this.client.target(this.config.getDfvApi().getUrl()).path("profile/sparte/ultimate")
                    .queryParam("token", this.config.getDfvApi().getToken())
                    .queryParam("secret", this.config.getDfvApi().getSecret());

            Invocation.Builder invocationBuilder = target.request(MediaType.APPLICATION_JSON);

            List<DfvMvName> response = invocationBuilder.get(new GenericType<List<DfvMvName>>() {});

            if (response != null) {
                Set<DfvMvName> nullNames = new HashSet<>();

                response.forEach(dfvMvName -> {
                    if (dfvMvName.getFirstName() == null || dfvMvName.getLastName() == null) {
                        nullNames.add(dfvMvName);
                    } else {
                        dfvMvName.setFirstName(dfvMvName.getFirstName().trim());
                        dfvMvName.setLastName(dfvMvName.getLastName().trim());
                    }
                });

                if (nullNames.size() > 0) {
                    LOGGER.warn("Found null entries in dfv-mv data: " +
                            nullNames.stream()
                                    .map(DfvMvName::getDfvNumber)
                                    .map(String::valueOf)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));
                }

                this.dataStore.refreshDfvNames(response);
                List<DfvPlayer> playersToUpdate = this.dataStore.getPlayersToUpdate();
                if (playersToUpdate != null) {
                    // TODO: debug do remove
                    LOGGER.info("Updating players " + playersToUpdate.stream()
                            .map(DfvPlayer::getDfvNumber)
                            .map(String::valueOf)
                            .sorted()
                            .collect(Collectors.joining(", ")));
                    for (DfvPlayer player : playersToUpdate) {
                        this.updatePlayerData(player);
                        this.validateRosterParticipation(player);
                    }
                }
            }

            return true;
        }
    }

    private void validateRosterParticipation(DfvPlayer updatedPlayer) {
        if (!updatedPlayer.isEligible()) {

            List<Roster> rosters = this.dataStore.getRosterForPlayer(updatedPlayer);
            final LocalDateTime now = LocalDateTime.now();
            final int currentYear = now.getYear();

            for (Roster roster : rosters) {
                if (roster.getSeason() != null && roster.getSeason().getYear() == currentYear) {
                    LOGGER.info(
                            "Player {} {} (Dfv-number {}, id {}) is no longer eligible, but listed in roster {} {} {} {} {} {} (id={})",
                            updatedPlayer.getFirstName(), updatedPlayer.getLastName(), updatedPlayer.getDfvNumber(),
                            updatedPlayer.getId(), roster.getTeam().getName(), roster.getNameAddition(),
                            roster.getSeason().getYear(), roster.getDivisionType(), roster.getDivisionAge(),
                            roster.getSeason().getSurface(), roster.getId());
                    String firstParagraph = this.buildParagraph(updatedPlayer, roster);
                    String explainParagraph = "Die Gründe dafür können sein:\n\tDer Spieler wurde von seinem Verein noch nicht für das nächste Kalenderjahr gemeldet\n\tDer Spieler ist ab sofort passiv gemeldet\n\tDer Spieler hat seine Datenschutzerklärung zurück gezogen.\n\tDer Spieler ist in der DFV-Mitgliederverwaltung keiner oder der falschen Sparte zugeordnet\n\tDie Verbandsbeiträge für den Spieler wurden noch nicht bezahlt.";

                    for (User admin : roster.getTeam().getAdmins()) {
                        this.sendMailToAdmin(firstParagraph, explainParagraph, admin);
                    }
                } else if (roster.getSeason().getYear() > currentYear) {
                    this.dataStore.removePlayerFromRoster(updatedPlayer.getId(), roster.getId());
                    LOGGER.info("Removed player {} {} (Dfv-number {} id {}) from roster {}",
                            updatedPlayer.getFirstName(), updatedPlayer.getLastName(), updatedPlayer.getDfvNumber(),
                            updatedPlayer.getId(), roster.getId());
                }
            }
        }
    }

    private void sendMailToAdmin(String firstParagraph, String explainParagraph, User admin) {
        LOGGER.debug("Sending mail to {} ...", admin.getEmail());
        SystemMessage sm = new SystemMessage();
        sm.addParagraph(firstParagraph);
        sm.addParagraph(explainParagraph);
        sm.addRecipient(admin.getEmail(), admin.getDfvPlayer().getFirstName(),
                admin.getDfvPlayer().getFirstName() + " " + admin.getDfvPlayer().getLastName());
        sm.setSubject("dfv-turniere.de - Spieler ohne Spielberechtigung");
        this.mailClient.sendMail(sm);
        LOGGER.debug("... mail sent");
    }

    private String buildParagraph(DfvPlayer updatedPlayer, Roster roster) {
        StringBuilder sb = new StringBuilder();
        sb.append("Der Spieler ").append(updatedPlayer.getFirstName()).append(' ').append(updatedPlayer.getLastName())
                .append(" (Dfv-Nummer: ").append(updatedPlayer.getDfvNumber()).append(')');
        sb.append(" wurde für die ggf. noch ausstehenden Turniere aus dem Roster ").append(roster.getTeam().getName())
                .append(' ');
        if (roster.getNameAddition() != null && !roster.getNameAddition().isEmpty()) {
            sb.append(roster.getNameAddition());
            sb.append(' ');
        }
        sb.append(" für die Saison ").append(roster.getSeason().getYear()).append(' ').append(roster.getDivisionType())
                .append(' ');
        if (roster.getDivisionAge() != DivisionAge.REGULAR) {
            sb.append(roster.getDivisionAge()).append(' ');
        }
        sb.append(roster.getSeason().getSurface()).append(' ');
        sb.append("entfernt, da er die Vorraussetzungen für eine Spielberechtigung im DFV nicht mehr erfüllt.");
        String firstParagraph = sb.toString();
        return firstParagraph;
    }

    private void updatePlayerData(DfvPlayer updatedPlayer) {
        DfvMvName mvName = this.dataStore.getDfvMvName(updatedPlayer.getDfvNumber());
        DfvMvPlayer mvPlayer = this.getMvPlayer(updatedPlayer, mvName);

        if (mvName != null && mvPlayer != null) {
            // TODO: put back to debug
            LOGGER.info(
                    "Updated player (id={}) to the following values: firstName={}, lastName={}, lastModified={}, eligible={}, gender={}, birthDate={}, email={}, clubId={}",
                    updatedPlayer.getId(), updatedPlayer.getFirstName(), updatedPlayer.getLastName(),
                    updatedPlayer.getLastModified(), updatedPlayer.isEligible(), updatedPlayer.getGender(),
                    updatedPlayer.getBirthDate(), updatedPlayer.getEmail(), updatedPlayer.getClub() != null ? updatedPlayer.getClub().getId() : "no club");
            this.updatePlayer(updatedPlayer, mvName, mvPlayer);
        } else if (updatedPlayer.isEligible()) {
            // for some reason we did not find a matching
            // player and the DfvPlayer is still eligible, so we deactivate the
            // player we have
            // TODO: put back to debug
            LOGGER.info(
                    "Deactivated player in our DB with id={}, dfvnumber={} that could not be loaded from the dfv-mv database!",
                    updatedPlayer.getId(), updatedPlayer.getDfvNumber());
            final LocalDateTime eligibleUntil = mvName != null ? mvName.getLastModified() : LocalDateTime.now();
            updatedPlayer.setEligibleUntil(eligibleUntil);
            // set modified datetime to now - 1 hour to prevent racing
            // conditions if it is re-activated right now
            updatedPlayer.setLastModified(LocalDateTime.now().minusHours(1));
        }
        this.dataStore.updateDfvPlayer(updatedPlayer);
        LOGGER.debug("stored updated player in db");
    }

    private void updatePlayer(DfvPlayer player, DfvMvName mvName, DfvMvPlayer mvPlayer) {
        player.setFirstName(mvName.getFirstName());
        player.setLastName(mvName.getLastName());
        player.setLastModified(mvName.getLastModified());
        player.setPaid(mvPlayer.isPaid());

        Policy policy = Policy.getPolicy("DFV", dataStore);

        if (policy.getPlayerEligibility(mvPlayer) == Policy.Eligibility.ELIGIBLE) {
            player.setEligibleUntil(null);
        } else {
            player.setEligibleUntil(mvName.getLastModified());
        }

        player.setGender(Gender.robustValueOf(mvPlayer.getGender()));
        player.setBirthDate(LocalDate.parse(mvPlayer.getDobString()));
        player.setEmail(mvPlayer.getEmail());

        Club club = new Club();
        club.setId(mvName.getClub().getId());
        player.setClub(club);
    }

    private DfvMvPlayer getMvPlayer(DfvPlayer player, DfvMvName mvName) {
        /*
         * Would be great if the WebTarget could be saved as a template ...
         */
        WebTarget playerProfilTarget = this.client.target(this.config.getDfvApi().getUrl()).path("profil")
                .path(String.valueOf(player.getDfvNumber())).queryParam("token", this.config.getDfvApi().getToken())
                .queryParam("secret", this.config.getDfvApi().getSecret());
        Invocation.Builder playerInvocationBuilder = playerProfilTarget.request(MediaType.APPLICATION_JSON);

        DfvMvPlayer mvPlayer = null;

        try {
            mvPlayer = playerInvocationBuilder.get(DfvMvPlayer.class);
        } catch (NotFoundException e) {
            LOGGER.error(String.format("failed to load player=%d", player.getDfvNumber()), e);
            return null;
        }
        if (mvName != null) {
            mvPlayer.setClub(mvName.getClub().getId());
        }
        return mvPlayer;
    }

}

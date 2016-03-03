'use strict';

angular.module('ultical.events')

.controller('EventRegistrationCtrl', ['$scope', 'storage', '$filter', '$rootScope', 'alerter',
  function($scope, storage, $filter, $rootScope, alerter) {

    var ownTeams = [];
    var ownTeamsByDivision = [];

    $scope.teamRegistrationPending = false;
    $rootScope.teamRegistrationDisabled = false;

    $scope.register = {};
    $scope.register.division = $scope.event.x.divisions[0];

    $scope.eventName = $scope.event.x.isSingleEvent ? $filter('eventname')($scope.event) : $filter('editionname')($scope.event.tournamentEdition);

    $scope.changeDivision = function() {
      ownTeamsByDivision = angular.copy(ownTeams);

      // add sub-teams
      angular.forEach(ownTeams, function(team) {
        angular.forEach(team.rosters, function(roster) {

          if (roster.season.id == $scope.event.tournamentEdition.season.id && roster.divisionAge == $scope.register.division.divisionAge && roster.divisionType == $scope.register.division.divisionType) {
            // check for context equality (or null)
            if ((isEmpty(roster.context) && isEmpty($scope.event.tournamentEdition.context)) || (!isEmpty(roster.context) && !isEmpty($scope.event.tournamentEdition.context) && roster.context.id == $scope.event.tournamentEdition.context.id)) {
              // only add subteams (the main teams have already be added above)
              if (!isEmpty(roster.nameAddition)) {
                ownTeamsByDivision.push({
                  team: team,
                  roster: roster,
                  name: team.name + ' ' + roster.nameAddition
                });
              }
            }
          }
        });
      });

      // sort
      ownTeamsByDivision = $filter('orderLocaleBy')(ownTeamsByDivision, 'name');

      // preset team
      if (ownTeamsByDivision.length > 0) {
        $scope.chosenTeam = ownTeamsByDivision[0];
      }
    };

    $scope.getOwnTeams = function() {
      return ownTeamsByDivision;
    };

    storage.getOwnTeams(function(teams) {
      ownTeams = teams;
      $scope.changeDivision();
    });

    $scope.newRosterCreation = false;
    $scope.startNewRosterCreation = function() {
      $scope.newRosterCreation = true;
    };
    $scope.cancelNewRosterCreation = function() {
      $scope.newRosterCreation = false;
      $scope.newNameAddition = '';
    };

    $scope.getTranslateParams = function(team) {
      return { teamName: $scope.getTeam(team) ? $scope.getTeam(team).name : ''};
    }

    $scope.getTeam = function(team) {
      if (isEmpty(team)) {
        return null;
      }
      if ('id' in team) {
        return team;
      } else {
        return team.team;
      }
    };

    $scope.newNameAddition = {text: ''};

    $rootScope.doTeamRegister = function() {
      $scope.teamRegistrationPending = true;
      $rootScope.teamRegistrationDisabled = true;

      var existingRosterForRegistration = null;

      if ($scope.newRosterCreation) {
        // a new roster ought to be created
        if (isEmpty($scope.newNameAddition.text)) {
          // new name addition request but not put into place - error
          alerter.error('', 'event.register.nameAdditionEmpty', {
            container: '#event-registration-error',
            duration: 10,
          });
          $scope.teamRegistrationPending = false;
          $rootScope.teamRegistrationDisabled = false;
          return;
        }
      } else {
        // check if the chosen team already has a roster
        if ('id' in $scope.chosenTeam) {
          // this is a base-team - let's check if a roster is present
          angular.forEach($scope.chosenTeam.rosters, function(roster) {
            if (roster.season.id == $scope.event.tournamentEdition.season.id && roster.divisionAge == $scope.register.division.divisionAge && roster.divisionType == $scope.register.division.divisionType) {
              // check for context equality (or null)
              if ((isEmpty(roster.context) && isEmpty($scope.event.tournamentEdition.context)) || (!isEmpty(roster.context) && !isEmpty($scope.event.tournamentEdition.context) && roster.context.id == $scope.event.tournamentEdition.context.id)) {
                // only look for rosters for the 'base' team
                if (isEmpty(roster.nameAddition)) {
                  existingRosterForRegistration = roster;
                }
              }
            }
          });
        } else {
          existingRosterForRegistration = $scope.chosenTeam.roster;
        }
      }

      if (existingRosterForRegistration == null) {

        // save roster
        var rosterToCreate = {
          id: -1,
          divisionType: $scope.register.division.divisionType,
          divisionAge: $scope.register.division.divisionAge,
          season: $scope.event.tournamentEdition.season,
          context: $scope.event.tournamentEdition.context,
          nameAddition: $scope.newNameAddition.text,
          team: $scope.getTeam($scope.chosenTeam),
        }

        storage.saveRoster(rosterToCreate, rosterToCreate.team, function(newRoster) {
          doRegister(newRoster);
        }, function(errorResponse) {
          if (errorResponse.status = 409) {
            alerter.error('', 'event.register.rosterDuplicated', {
              container: '#event-registration-error',
              duration: 10,
            });
          }
          $scope.teamRegistrationPending = false;
          $rootScope.teamRegistrationDisabled = false;
        });
      } else {
        // choose roster to register
        doRegister(existingRosterForRegistration);
      }
    };

    function doRegister(roster) {
      var registration = {};
      registration.comment = $scope.register.comment ? $scope.register.comment : null;
      registration.team = { id: $scope.getTeam($scope.chosenTeam).id};
      registration.roster = { id: roster.id};

      var division = {};
      angular.forEach($scope.event.x.divisions, function(div) {
        if (div.id == $scope.register.division.id) {
          division = div;
        }
      });

      storage.registerTeamForEdition(registration, division, function(newTeamReg) {
        newTeamReg.team = $scope.getTeam($scope.chosenTeam);
        newTeamReg.roster = roster;
        $scope.$hide();
      }, function(errorResponse) {
        $scope.teamRegistrationPending = false;
        $rootScope.teamRegistrationDisabled = false;
      });
    };

  }
]);

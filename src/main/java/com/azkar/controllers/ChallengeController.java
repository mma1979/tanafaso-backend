package com.azkar.controllers;

import com.azkar.entities.Challenge;
import com.azkar.entities.Challenge.SubChallenge;
import com.azkar.entities.Group;
import com.azkar.entities.User;
import com.azkar.entities.User.UserGroup;
import com.azkar.payload.ResponseBase.Status;
import com.azkar.payload.challengecontroller.requests.AddChallengeRequest;
import com.azkar.payload.challengecontroller.requests.AddPersonalChallengeRequest;
import com.azkar.payload.challengecontroller.requests.UpdateChallengeRequest;
import com.azkar.payload.challengecontroller.responses.AddChallengeResponse;
import com.azkar.payload.challengecontroller.responses.AddPersonalChallengeResponse;
import com.azkar.payload.challengecontroller.responses.GetChallengeResponse;
import com.azkar.payload.challengecontroller.responses.GetChallengesResponse;
import com.azkar.payload.challengecontroller.responses.UpdateChallengeResponse;
import com.azkar.payload.exceptions.BadRequestException;
import com.azkar.repos.ChallengeRepo;
import com.azkar.repos.GroupRepo;
import com.azkar.repos.UserRepo;
import com.azkar.services.NotificationsService;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/challenges", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChallengeController extends BaseController {

  private static final Logger logger = LoggerFactory.getLogger(ChallengeController.class);

  @Autowired
  NotificationsService notificationsService;
  @Autowired
  UserRepo userRepo;
  @Autowired
  ChallengeRepo challengeRepo;
  @Autowired
  GroupRepo groupRepo;

  private static Predicate<Challenge> all() {
    return (userChallengeStatus -> true);
  }

  // Note: This function may modify oldSubChallenges.
  private static Optional<ResponseEntity<UpdateChallengeResponse>> updateOldSubChallenges(
      List<SubChallenge> oldSubChallenges,
      List<SubChallenge> newSubChallenges) {
    UpdateChallengeResponse response = new UpdateChallengeResponse();
    if (newSubChallenges.size() != oldSubChallenges.size()) {
      response
          .setStatus(new Status(Status.MISSING_OR_DUPLICATED_SUB_CHALLENGE_ERROR));
      return Optional.of(ResponseEntity.unprocessableEntity().body(response));
    }

    // Set to make sure that the zekr IDs of both old and modified sub-challenges are identical.
    Set<Integer> newZekrIds = new HashSet<>();
    for (SubChallenge newSubChallenge : newSubChallenges) {
      newZekrIds.add(newSubChallenge.getZekr().getId());
      Optional<SubChallenge> subChallenge = findSubChallenge(oldSubChallenges, newSubChallenge);
      if (!subChallenge.isPresent()) {
        response.setStatus(new Status(Status.NON_EXISTENT_SUB_CHALLENGE_ERROR));
        return Optional.of(ResponseEntity.unprocessableEntity().body(response));
      }
      Optional<Status> error = updateSubChallenge(subChallenge.get(), newSubChallenge);
      if (error.isPresent()) {
        response.setStatus(error.get());
        return Optional.of(ResponseEntity.unprocessableEntity().body(response));
      }
    }
    if (newZekrIds.size() != oldSubChallenges.size()) {
      response
          .setStatus(new Status(Status.MISSING_OR_DUPLICATED_SUB_CHALLENGE_ERROR));
      return Optional.of(ResponseEntity.unprocessableEntity().body(response));
    }
    return Optional.empty();
  }

  private static void updateScore(User user, String groupId) {
    Optional<UserGroup> group =
        user.getUserGroups().stream().filter(userGroup -> userGroup.getGroupId().equals(groupId))
            .findAny();
    if (!group.isPresent()) {
      throw new RuntimeException("The updated challenge is not in a group.");
    }
    int oldScore = group.get().getTotalScore();
    group.get().setTotalScore(oldScore + 1);
  }

  private static Optional<SubChallenge> findSubChallenge(
      List<SubChallenge> oldSubChallenges,
      SubChallenge newSubChallenge) {
    for (SubChallenge subChallenge : oldSubChallenges) {
      if (subChallenge.getZekr().getId().equals(newSubChallenge.getZekr().getId())) {
        return Optional.of(subChallenge);
      }
    }
    return Optional.empty();
  }

  /**
   * Updates the subChallenge as requested in newSubChallenge. If an error occurred the function
   * returns an error, and returns empty object otherwise.
   */
  private static Optional<Status> updateSubChallenge(
      SubChallenge subChallenge,
      SubChallenge newSubChallenge) {
    int newLeftRepetitions = newSubChallenge.getRepetitions();
    if (newLeftRepetitions > subChallenge.getRepetitions()) {
      return Optional.of(new Status(Status.INCREMENTING_LEFT_REPETITIONS_ERROR));
    }
    if (newLeftRepetitions < 0) {
      logger.warn("Received UpdateChallenge request with negative leftRepetition value of: "
          + newLeftRepetitions);
      newLeftRepetitions = 0;
    }
    subChallenge.setRepetitions(newLeftRepetitions);
    return Optional.empty();
  }

  @PostMapping(path = "/personal", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AddPersonalChallengeResponse> addPersonalChallenge(
      @RequestBody AddPersonalChallengeRequest request) {
    AddPersonalChallengeResponse response = new AddPersonalChallengeResponse();
    // TODO(issue#36): Allow this method to throw the exception and handle it on a higher level.
    try {
      request.validate();
    } catch (BadRequestException e) {
      response.setStatus(e.error);
      return ResponseEntity.badRequest().body(response);
    }

    Challenge challenge = request.getChallenge();
    challenge = challenge.toBuilder()
        .id(UUID.randomUUID().toString())
        .groupId(Challenge.PERSONAL_CHALLENGES_NON_EXISTING_GROUP_ID)
        .creatingUserId(getCurrentUser().getUserId())
        .createdAt(Instant.now().getEpochSecond())
        .modifiedAt(Instant.now().getEpochSecond())
        .build();

    User loggedInUser = getCurrentUser(userRepo);
    loggedInUser.getPersonalChallenges().add(challenge);
    userRepo.save(loggedInUser);
    response.setData(challenge);
    return ResponseEntity.ok(response);
  }

  @GetMapping(path = "/personal")
  public ResponseEntity<GetChallengesResponse> getPersonalChallenges() {
    GetChallengesResponse response = new GetChallengesResponse();
    response.setData(getCurrentUser(userRepo).getPersonalChallenges());
    Collections.reverse(response.getData());
    return ResponseEntity.ok(response);
  }

  @PutMapping(path = "/personal/{challengeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<UpdateChallengeResponse> updatePersonalChallenge(
      @PathVariable(value = "challengeId") String challengeId,
      @RequestBody UpdateChallengeRequest request) {
    User currentUser = getCurrentUser(userRepo);
    Optional<Challenge> personalChallenge = currentUser.getPersonalChallenges().stream()
        .filter(personalChallengeItem -> personalChallengeItem.getId().equals(challengeId))
        .findAny();
    if (!personalChallenge.isPresent()) {
      UpdateChallengeResponse response = new UpdateChallengeResponse();
      response.setStatus(new Status(Status.CHALLENGE_NOT_FOUND_ERROR));
      return ResponseEntity.badRequest().body(response);
    }
    if (personalChallenge.get().getExpiryDate() < Instant.now().getEpochSecond()) {
      UpdateChallengeResponse response = new UpdateChallengeResponse();
      response.setStatus(new Status(Status.CHALLENGE_EXPIRED_ERROR));
      return ResponseEntity.badRequest().body(response);
    }

    List<SubChallenge> oldSubChallenges = personalChallenge.get().getSubChallenges();
    // Note: It is ok to change the old sub-challenges even if there was an error since we don't
    // save the updated user object unless there are no errors.
    Optional<ResponseEntity<UpdateChallengeResponse>> errorResponse = updateOldSubChallenges(
        oldSubChallenges, request.getNewChallenge().getSubChallenges());

    if (errorResponse.isPresent()) {
      return errorResponse.get();
    }
    userRepo.save(currentUser);
    return ResponseEntity.ok().build();
  }

  @GetMapping("{challengeId}")
  public ResponseEntity<GetChallengeResponse> getChallenge(
      @PathVariable(value = "challengeId") String challengeId) {
    GetChallengeResponse response = new GetChallengeResponse();
    Optional<Challenge> userChallenge = getCurrentUser(userRepo).getUserChallenges()
        .stream()
        .filter(challenge -> challenge.getId().equals(challengeId))
        .findFirst();
    if (!userChallenge.isPresent()) {
      response.setStatus(new Status(Status.CHALLENGE_NOT_FOUND_ERROR));
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
    response.setData(userChallenge.get());
    return ResponseEntity.ok(response);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AddChallengeResponse> addGroupChallenge(
      @RequestBody AddChallengeRequest req) {
    AddChallengeResponse response = new AddChallengeResponse();
    try {
      req.validate();
    } catch (BadRequestException e) {
      response.setStatus(e.error);
      return ResponseEntity.badRequest().body(response);
    }
    Optional<Group> group = groupRepo.findById(req.getChallenge().getGroupId());
    if (!group.isPresent()) {
      response.setStatus(new Status(Status.GROUP_NOT_FOUND_ERROR));
      return ResponseEntity.badRequest().body(response);
    }
    if (!groupContainsCurrentUser(group.get())) {
      response.setStatus(new Status(Status.NOT_GROUP_MEMBER_ERROR));
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }
    User currentUser = getCurrentUser(userRepo);
    Challenge challenge = req.getChallenge().toBuilder()
        .creatingUserId(currentUser.getId())
        .build();
    challengeRepo.save(challenge);

    group.get().getChallengesIds().add(challenge.getId());
    groupRepo.save(group.get());

    List<String> groupUsersIds = group.get().getUsersIds();
    Iterable<User> affectedUsers = userRepo.findAllById(groupUsersIds);
    affectedUsers.forEach(user -> user.getUserChallenges().add(challenge));
    affectedUsers.forEach(affectedUser -> {
      if (!affectedUser.getId().equals(currentUser.getId())) {
        notificationsService.sendNotificationToUser(affectedUser, "لديك تحدٍ جديد",
            "تحداك" + " " + currentUser.getFirstName() + " " + currentUser.getLastName());
      }
    });
    userRepo.saveAll(affectedUsers);

    response.setData(challenge);
    return ResponseEntity.ok(response);
  }

  private boolean groupContainsCurrentUser(Group group) {
    return group.getUsersIds().contains(getCurrentUser().getUserId());
  }

  // Returns all non-personal challenges.
  @GetMapping(path = "/")
  public ResponseEntity<GetChallengesResponse> getAllChallenges() {
    GetChallengesResponse response = new GetChallengesResponse();
    response.setData(getCurrentUser(userRepo).getUserChallenges());
    Collections.reverse(response.getData());
    return ResponseEntity.ok(response);
  }

  @GetMapping(path = "/groups/{groupId}/")
  public ResponseEntity<GetChallengesResponse> getAllChallengesInGroup(
      @PathVariable(value = "groupId") String groupId) {
    Optional<Group> optionalGroup = groupRepo.findById(groupId);
    ResponseEntity<GetChallengesResponse> error = validateGroupAndReturnError(optionalGroup);

    if (error != null) {
      return error;
    }

    List<Challenge> challengesInGroup =
        getCurrentUser(userRepo).getUserChallenges().stream()
            .filter((challenge -> challenge.getGroupId().equals(groupId))).collect(
            Collectors.toList());

    Collections.reverse(challengesInGroup);
    GetChallengesResponse response = new GetChallengesResponse();
    response.setData(challengesInGroup);
    return ResponseEntity.ok(response);
  }


  private ResponseEntity<GetChallengesResponse> validateGroupAndReturnError(
      Optional<Group> optionalGroup) {
    GetChallengesResponse response = new GetChallengesResponse();
    if (!optionalGroup.isPresent()) {
      response.setStatus(new Status(Status.GROUP_NOT_FOUND_ERROR));
      return ResponseEntity.badRequest().body(response);
    }
    if (!groupContainsCurrentUser(optionalGroup.get())) {
      response.setStatus(new Status(Status.NON_GROUP_MEMBER_ERROR));
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }
    return null;
  }

  @PutMapping(path = "/{challengeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<UpdateChallengeResponse> updateChallenge(
      @PathVariable(value = "challengeId") String challengeId,
      @RequestBody UpdateChallengeRequest request) {
    User currentUser = getCurrentUser(userRepo);
    Optional<Challenge> currentUserChallenge = currentUser.getUserChallenges()
        .stream()
        .filter(challenge -> challenge.getId().equals(challengeId))
        .findFirst();
    if (!currentUserChallenge.isPresent()) {
      UpdateChallengeResponse response = new UpdateChallengeResponse();
      response.setStatus(new Status(Status.CHALLENGE_NOT_FOUND_ERROR));
      return ResponseEntity.badRequest().body(response);
    }
    // TODO(issue#170): Time should be supplied by a bean to allow easier testing
    if (currentUserChallenge.get().getExpiryDate() < Instant.now().getEpochSecond()) {
      UpdateChallengeResponse response = new UpdateChallengeResponse();
      response.setStatus(new Status(Status.CHALLENGE_EXPIRED_ERROR));
      return ResponseEntity.badRequest().body(response);
    }

    List<SubChallenge> oldSubChallenges = currentUserChallenge.get().getSubChallenges();
    boolean oldSubChallengesFinished =
        oldSubChallenges.stream().allMatch(subChallenge -> (subChallenge.getRepetitions() == 0));
    Optional<ResponseEntity<UpdateChallengeResponse>> errorResponse = updateOldSubChallenges(
        oldSubChallenges, request.getNewChallenge().getSubChallenges());
    if (errorResponse.isPresent()) {
      return errorResponse.get();
    }
    boolean newSubChallengesFinished =
        oldSubChallenges.stream().allMatch(subChallenge -> (subChallenge.getRepetitions() == 0));
    if (newSubChallengesFinished && !oldSubChallengesFinished) {
      updateScore(currentUser, currentUserChallenge.get().getGroupId());
    }
    userRepo.save(currentUser);
    return ResponseEntity.ok().build();
  }
}

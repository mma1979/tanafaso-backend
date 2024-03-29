package com.azkar.controllers.friendshipcontroller;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.azkar.TestBase;
import com.azkar.controllers.utils.AzkarApi;
import com.azkar.controllers.utils.JsonHandler;
import com.azkar.entities.Challenge;
import com.azkar.entities.Friendship;
import com.azkar.entities.Friendship.Friend;
import com.azkar.entities.Group;
import com.azkar.entities.User;
import com.azkar.factories.entities.ChallengeFactory;
import com.azkar.factories.entities.UserFactory;
import com.azkar.payload.ResponseBase.Status;
import com.azkar.payload.challengecontroller.requests.UpdateChallengeRequest;
import com.azkar.payload.usercontroller.responses.AddFriendResponse;
import com.azkar.payload.usercontroller.responses.DeleteFriendResponse;
import com.azkar.payload.usercontroller.responses.GetFriendsLeaderboardResponse;
import com.azkar.payload.usercontroller.responses.GetFriendsLeaderboardResponse.FriendshipScores;
import com.azkar.payload.usercontroller.responses.GetFriendsResponse;
import com.azkar.payload.usercontroller.responses.ResolveFriendRequestResponse;
import com.azkar.payload.utils.UserScore;
import com.azkar.repos.ChallengeRepo;
import com.azkar.repos.FriendshipRepo;
import com.azkar.repos.GroupRepo;
import com.azkar.repos.UserRepo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

public class FriendshipTest extends TestBase {

  private static final int SUB_CHALLENGES_REPETITIONS = 2;

  private static User USER1 = UserFactory.getNewUser();
  private static User USER2 = UserFactory.getNewUser();
  private static User USER3 = UserFactory.getNewUser();
  private static User USER4 = UserFactory.getNewUser();
  private static User USER5 = UserFactory.getNewUser();
  private static User UNAUTHENTICATED_USER = UserFactory.getNewUser();

  @Autowired
  FriendshipRepo friendshipRepo;

  @Autowired
  GroupRepo groupRepo;

  @Autowired
  ChallengeRepo challengeRepo;

  @Autowired
  UserRepo userRepo;

  @Autowired
  AzkarApi azkarApi;

  @Before
  public void before() {
    addNewUser(USER1);
    addNewUser(USER2);
    addNewUser(USER3);
    addNewUser(USER4);
    addNewUser(USER5);
  }

  @Test
  public void addFriend_normalScenario_shouldSucceed() throws Exception {
    AddFriendResponse expectedResponse = new AddFriendResponse();
    assertThat(groupRepo.count(), equalTo(0L));

    azkarApi.sendFriendRequest(USER1, USER2)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    Friendship user1Friendship = friendshipRepo.findByUserId(USER1.getId());
    Friendship user2Friendship = friendshipRepo.findByUserId(USER2.getId());

    assertThat(user1Friendship.getFriends().size(), is(0));
    assertThat(user2Friendship.getFriends().size(), is(1));

    Friend user2Friend = user2Friendship.getFriends().get(0);
    assertFriendship(user2Friend, USER1, /*isPending=*/true);

    assertThat(groupRepo.count(), equalTo(0L));
  }

  @Test
  public void addFriend_requesterRequestedBefore_shouldNotSucceed() throws Exception {
    assertThat(groupRepo.count(), equalTo(0L));

    azkarApi.sendFriendRequest(USER1, USER2);

    AddFriendResponse expectedResponse = new AddFriendResponse();
    expectedResponse.setStatus(new Status(Status.FRIENDSHIP_ALREADY_REQUESTED_ERROR));

    azkarApi.sendFriendRequest(USER1, USER2)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    assertThat(groupRepo.count(), equalTo(0L));
  }


  @Test
  public void addFriend_responderRequestedBefore_shouldAddNonPendingFriendship() throws Exception {
    azkarApi.sendFriendRequest(USER1, USER2);

    AddFriendResponse expectedResponse = new AddFriendResponse();
    azkarApi.sendFriendRequest(USER2, USER1)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    Friendship user1Friendship = friendshipRepo.findByUserId(USER1.getId());
    Friendship user2Friendship = friendshipRepo.findByUserId(USER2.getId());

    assertThat(user1Friendship.getFriends().size(), is(1));
    assertThat(user2Friendship.getFriends().size(), is(1));

    Friend user1Friend = user1Friendship.getFriends().get(0);
    assertFriendship(user1Friend, USER2, /*isPending=*/false);

    Friend user2Friend = user2Friendship.getFriends().get(0);
    assertFriendship(user2Friend, USER1, /*isPending=*/false);
  }


  @Test
  public void addFriend_invalidResponder_shouldNotSucceed() throws Exception {
    AddFriendResponse expectedResponse = new AddFriendResponse();
    expectedResponse.setStatus(new Status(Status.USER_NOT_FOUND_ERROR));

    azkarApi.sendFriendRequest(USER1, UNAUTHENTICATED_USER)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }

  @Test
  public void addFriend_addSelf_shouldNotSucceed() throws Exception {
    AddFriendResponse expectedResponse = new AddFriendResponse();
    expectedResponse.setStatus(new Status(Status.ADD_SELF_ERROR));

    azkarApi.sendFriendRequest(USER1, USER1)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }

  @Test
  public void acceptFriendRequest_normalScenario_shouldSucceed() throws Exception {
    assertThat(groupRepo.count(), equalTo(0L));
    assertThat(USER1.getUserGroups().size(), equalTo(0));
    assertThat(USER2.getUserGroups().size(), equalTo(0));

    azkarApi.sendFriendRequest(USER1, USER2);

    ResolveFriendRequestResponse expectedResponse = new ResolveFriendRequestResponse();
    azkarApi.acceptFriendRequest(USER2, USER1)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    Friendship user1Friendship = friendshipRepo.findByUserId(USER1.getId());
    Friendship user2Friendship = friendshipRepo.findByUserId(USER2.getId());

    assertThat(user1Friendship.getFriends().size(), is(1));
    assertThat(user2Friendship.getFriends().size(), is(1));

    Friend user1Friend = user1Friendship.getFriends().get(0);
    assertFriendship(user1Friend, USER2, /*isPending=*/false);

    Friend user2Friend = user2Friendship.getFriends().get(0);
    assertFriendship(user2Friend, USER1, /*isPending=*/false);

    assertThat(groupRepo.count(), equalTo(1L));
    Group group = Iterators.getOnlyElement(groupRepo.findAll().iterator());
    assertThat(group.getUsersIds().size(), equalTo(2));
    assertThat(group.getUsersIds().contains(USER1.getId()), is(true));
    assertThat(group.getUsersIds().contains(USER2.getId()), is(true));

    assertThat(user1Friend.getGroupId(), notNullValue());
    assertThat(user1Friend.getGroupId(), equalTo(user2Friend.getGroupId()));

    User updatedUser1 = userRepo.findById(USER1.getId()).get();
    User updatedUser2 = userRepo.findById(USER2.getId()).get();
    assertThat(Iterators.getOnlyElement(updatedUser1.getUserGroups().iterator()).getGroupId(),
        equalTo(group.getId()));
    assertThat(Iterators.getOnlyElement(updatedUser2.getUserGroups().iterator()).getGroupId(),
        equalTo(group.getId()));
  }

  @Test
  public void rejectFriendRequest_normalScenario_shouldSucceed() throws Exception {
    assertThat(groupRepo.count(), equalTo(0L));

    azkarApi.sendFriendRequest(USER1, USER2);

    ResolveFriendRequestResponse expectedResponse = new ResolveFriendRequestResponse();
    azkarApi.rejectFriendRequest(USER2, USER1)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    Friendship user1Friendship = friendshipRepo.findByUserId(USER1.getId());
    Friendship user2Friendship = friendshipRepo.findByUserId(USER2.getId());

    assertThat(user1Friendship.getFriends().size(), is(0));
    assertThat(user2Friendship.getFriends().size(), is(0));
    assertThat(groupRepo.count(), equalTo(0L));
  }

  @Test
  public void acceptFriendRequest_friendshipNotPending_shouldNotSucceed() throws Exception {
    ResolveFriendRequestResponse expectedResponse = new ResolveFriendRequestResponse();
    expectedResponse
        .setStatus(new Status(Status.NO_FRIEND_REQUEST_EXIST_ERROR));
    azkarApi.acceptFriendRequest(USER1, USER2)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    Friendship user1Friendship = friendshipRepo.findByUserId(USER1.getId());
    Friendship user2Friendship = friendshipRepo.findByUserId(USER2.getId());

    assertThat(user1Friendship.getFriends().size(), is(0));
    assertThat(user2Friendship.getFriends().size(), is(0));
  }

  @Test
  public void resolveFriendship_noFriendshipExist_shouldNotSucceed() throws Exception {
    ResolveFriendRequestResponse expectedResponse = new ResolveFriendRequestResponse();
    expectedResponse
        .setStatus(new Status(Status.NO_FRIEND_REQUEST_EXIST_ERROR));

    azkarApi.acceptFriendRequest(USER1, USER2)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    azkarApi.rejectFriendRequest(USER2, USER1)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }

  @Test
  public void resolveFriendship_friendshipAlreadyExists_shouldNotSucceed() throws Exception {
    azkarApi.sendFriendRequest(USER1, USER2);
    azkarApi.acceptFriendRequest(USER2, USER1);

    ResolveFriendRequestResponse expectedResponse = new ResolveFriendRequestResponse();
    expectedResponse
        .setStatus(new Status(Status.FRIEND_REQUEST_ALREADY_ACCEPTED_ERROR));
    azkarApi.acceptFriendRequest(USER1, USER2)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    azkarApi.rejectFriendRequest(USER1, USER2)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    azkarApi.acceptFriendRequest(USER2, USER1)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    azkarApi.rejectFriendRequest(USER2, USER1)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }


  @Test
  public void getFriends_normalScenario_shouldSucceed() throws Exception {
    azkarApi.sendFriendRequest(USER1, USER5);

    azkarApi.makeFriends(USER1, USER2);
    azkarApi.makeFriends(USER3, USER4);
    azkarApi.makeFriends(USER3, USER1);

    // user1 expected friends.
    List<Friend> expectedUser1Friends = new ArrayList();
    expectedUser1Friends.add(Friend.builder()
        .userId(USER2.getId())
        .username(USER2.getUsername())
        .firstName(USER2.getFirstName())
        .lastName(USER2.getLastName())
        .isPending(false)
        .build()
    );
    expectedUser1Friends.add(Friend.builder()
        .userId(USER3.getId())
        .username(USER3.getUsername())
        .firstName(USER3.getFirstName())
        .lastName(USER3.getLastName())
        .isPending(false)
        .build()
    );

    MvcResult mvcResult =
        performGetRequest(USER1, "/friends")
            .andExpect(status().isOk())
            .andReturn();

    GetFriendsResponse getUser1FriendsResponse =
        JsonHandler
            .fromJson(mvcResult.getResponse().getContentAsString(), GetFriendsResponse.class);

    compareFriendshipList(getUser1FriendsResponse.getData().getFriends(), expectedUser1Friends);

    // user5 expected friends.
    List<Friend> expectedUser5Friends = new ArrayList();
    expectedUser5Friends.add(Friend.builder()
        .userId(USER1.getId())
        .username(USER1.getUsername())
        .firstName(USER1.getFirstName())
        .lastName(USER1.getLastName()).isPending(true)
        .build()
    );
    mvcResult = performGetRequest(USER5, "/friends")
        .andExpect(status().isOk())
        .andReturn();

    GetFriendsResponse getUser5FriendsResponse =
        JsonHandler
            .fromJson(mvcResult.getResponse().getContentAsString(), GetFriendsResponse.class);

    compareFriendshipList(getUser5FriendsResponse.getData().getFriends(), expectedUser5Friends);

  }

  @Test
  public void deleteFriend_normalScenario_shouldSucceed() throws Exception {
    azkarApi.makeFriends(USER1, USER2);
    assertThat(groupRepo.count(), equalTo(1L));
    User updatedUser1 = userRepo.findById(USER1.getId()).get();
    User updatedUser2 = userRepo.findById(USER2.getId()).get();
    assertThat(updatedUser1.getUserGroups().size(), equalTo(1));
    assertThat(updatedUser2.getUserGroups().size(), equalTo(1));

    DeleteFriendResponse expectedResponse = new DeleteFriendResponse();
    azkarApi.deleteFriend(USER1, USER2)
        .andExpect(status().isNoContent())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));

    Friendship user1Friendship = friendshipRepo.findByUserId(USER1.getId());
    Friendship user2Friendship = friendshipRepo.findByUserId(USER2.getId());

    assertThat(user1Friendship.getFriends().size(), is(0));
    assertThat(user2Friendship.getFriends().size(), is(0));

    assertThat(groupRepo.count(), equalTo(0L));
    updatedUser1 = userRepo.findById(USER1.getId()).get();
    updatedUser2 = userRepo.findById(USER2.getId()).get();
    assertThat(updatedUser1.getUserGroups().size(), equalTo(0));
    assertThat(updatedUser2.getUserGroups().size(), equalTo(0));
  }

  @Test
  public void deleteFriend_invalidUser_shouldSucceed() throws Exception {
    DeleteFriendResponse expectedResponse = new DeleteFriendResponse();
    expectedResponse.setStatus(new Status(Status.USER_NOT_FOUND_ERROR));

    azkarApi.deleteFriend(USER1, UNAUTHENTICATED_USER)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }

  @Test
  public void deleteFriend_friendRequestIsPending_shouldNotSucceed() throws Exception {
    azkarApi.sendFriendRequest(USER1, USER2);

    DeleteFriendResponse expectedResponse = new DeleteFriendResponse();
    expectedResponse.setStatus(new Status(Status.NO_FRIENDSHIP_ERROR));
    azkarApi.deleteFriend(USER1, USER2)
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }

  @Test
  public void deleteFriend_noFriendRequestExist_shouldNotSucceed() throws Exception {
    DeleteFriendResponse expectedResponse = new DeleteFriendResponse();
    expectedResponse.setStatus(new Status(Status.NO_FRIENDSHIP_ERROR));

    azkarApi.deleteFriend(USER1, USER2)
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }

  @Test
  public void getFriendsLeaderboard_normalScenario_shouldSucceed() throws Exception {
    User user1 = getNewRegisteredUser();
    User user2 = getNewRegisteredUser();
    User user3 = getNewRegisteredUser();
    User user4 = getNewRegisteredUser();

    // user1 is friends with user2 and user3 only.
    azkarApi.makeFriends(user1, user2);
    String user1And2FriendshipGroupId = getFriendshipGroupId(user1, user2);
    azkarApi.makeFriends(user1, user3);
    String user1And3FriendshipGroupId = getFriendshipGroupId(user1, user3);
    azkarApi.makeFriends(user2, user3);
    azkarApi.makeFriends(user3, user4);

    // Members = [user1, user2]
    Group group1 = azkarApi.addGroupAndReturn(user1, "group1");
    azkarApi.addUserToGroup(/*invitingUser=*/user1, user2, group1.getId());

    // Members = [user1, user2]
    Group group2 = azkarApi.addGroupAndReturn(user2, "group2");
    azkarApi.addUserToGroup(/*invitingUser=*/user2, user1, group2.getId());

    // Members = [user1, user2, user3]
    Group group3 = azkarApi.addGroupAndReturn(user1, "group3");
    azkarApi.addUserToGroup(/*invitingUser=*/user1, user2, group3.getId());
    azkarApi.addUserToGroup(/*invitingUser=*/user2, user3, group3.getId());

    // Members = [user3, user4]
    Group group4 = azkarApi.addGroupAndReturn(user3, "group4");
    azkarApi.addUserToGroup(/*invitingUser=*/user3, user4, group4.getId());

    Challenge challenge = createChallengeInGroup(user1, user1And2FriendshipGroupId);
    createChallengeInGroup(user1, user1And2FriendshipGroupId);
    finishChallenge(user1, challenge.getId());
    // Friends Scores Now:
    // [user1, user2] = [1, 0]
    // [user1, user3] = [0, 0]
    // [user1, user4] = [0, 0]

    challenge = createChallengeInGroup(user1, user1And3FriendshipGroupId);
    finishChallenge(user3, challenge.getId());
    // Friends Scores Now:
    // [user1, user2] = [1, 0]
    // [user1, user3] = [0, 1]

    challenge = createChallengeInGroup(user3, group4.getId());
    finishChallenge(user3, challenge.getId());
    // Friends Scores Now:
    // [user1, user2] = [1, 0]
    // [user1, user3] = [0, 1]

    challenge = createChallengeInGroup(user1, group1.getId());
    finishChallenge(user2, challenge.getId());
    // Friends Scores Now:
    // [user1, user2] = [1, 1]
    // [user1, user3] = [0, 1]

    challenge = createChallengeInGroup(user3, group3.getId());
    finishChallenge(user1, challenge.getId());
    // Friends Scores Now:
    // [user1, user2] = [2, 1]
    // [user1, user3] = [1, 1]

    GetFriendsLeaderboardResponse expectedResponse = new GetFriendsLeaderboardResponse();
    List<FriendshipScores> expectedFriendshipScores = ImmutableList.of(
        FriendshipScores.builder()
            .currentUserScore(2)
            .friendScore(1)
            .friend(
                Friend.builder().userId(user2.getId()).groupId(user1And2FriendshipGroupId).build())
            .build(),

        FriendshipScores.builder()
            .currentUserScore(1)
            .friendScore(1)
            .friend(
                Friend.builder().userId(user3.getId()).groupId(user1And3FriendshipGroupId).build())
            .build()

    );

    expectedResponse.setData(expectedFriendshipScores);
    azkarApi.getFriendsLeaderboard(user1)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(JsonHandler.toJson(expectedResponse)));
  }

  private UserScore buildUserScoreTemplateForUser(User user) {
    return UserScore.builder()
        .firstName(user.getFirstName())
        .lastName(user.getLastName())
        .username(user.getUsername())
        .build();
  }

  private int getLastAddedUserGroupIndex(User user1) {
    return userRepo.findById(user1.getId()).get().getUserGroups().size() - 1;
  }

  private void compareFriendshipList(List<Friend> actual, List<Friend> expected) {
    Collections.sort(actual, Comparator.comparing(Friend::getUserId));
    Collections.sort(expected, Comparator.comparing(Friend::getUserId));

    assertThat(actual.size(), equalTo(expected.size()));

    for (int i = 0; i < actual.size(); i++) {
      Friend actualFriend = actual.get(i);
      Friend expectedFriend = actual.get(i);
      assertThat(actualFriend.getUserId(), equalTo(expectedFriend.getUserId()));
      assertThat(actualFriend.getUsername(), equalTo(expectedFriend.getUsername()));
      assertThat(actualFriend.isPending(), equalTo(expectedFriend.isPending()));
    }
  }

  private Challenge createChallengeInGroup(User user, String groupId) throws Exception {
    Challenge challenge = ChallengeFactory.getNewChallenge(groupId);
    challenge.setSubChallenges(ImmutableList.of(ChallengeFactory.subChallenge1()));
    challenge.getSubChallenges().get(0).setRepetitions(SUB_CHALLENGES_REPETITIONS);
    azkarApi.addChallenge(user, challenge).andExpect(status().isOk());
    return challenge;
  }

  private void finishChallenge(User user, String challengeId) throws Exception {
    Challenge challenge = challengeRepo.findById(challengeId).get();
    assertThat(challenge.getSubChallenges().size(), is(1));
    challenge.getSubChallenges().get(0).setRepetitions(0);
    UpdateChallengeRequest request =
        UpdateChallengeRequest.builder().newChallenge(challenge).build();
    UpdateChallengeRequest.builder().newChallenge(challenge).build();
    azkarApi.updateChallenge(user, challengeId, request);
  }

  private String getFriendshipGroupId(User user1, User user2) throws Exception {
    return friendshipRepo.findAll().stream()
        .filter(friendship -> friendship.getUserId().equals(user1.getId()))
        .findFirst()
        .get()
        .getFriends()
        .stream()
        .filter(friend -> friend.getUserId().equals(user2.getId()))
        .findFirst()
        .get()
        .getGroupId();
  }

  private void assertFriendship(Friend friend, User user, boolean isPending) {
    assertThat(friend.getUserId(), equalTo(user.getId()));
    assertThat(friend.getUsername(), equalTo(user.getUsername()));
    assertThat(friend.isPending(), is(isPending));
  }
}

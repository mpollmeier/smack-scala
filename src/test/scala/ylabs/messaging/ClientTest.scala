package smack.scala

import Client.{ Password, User }
import Client.Messages._
import akka.actor.{ ActorSystem, Props }
import akka.pattern.ask
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import java.util.UUID
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.Roster
import org.scalatest
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }
import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Success, Try }

// this test depends on a running xmpp server (e.g. ejabberd) configured so that admin users can create unlimited users in your environment!
// see http://docs.ejabberd.im/admin/guide/configuration/#modregister for more details
@tags.RequiresEjabberd
class ClientTest extends WordSpec with Matchers with BeforeAndAfterEach {
  implicit var system: ActorSystem = _
  implicit val timeout = Timeout(5 seconds)

  val config = ConfigFactory.load
  val adminUsername = config.getString("messaging.admin.username")
  val adminPassword = config.getString("messaging.admin.password")
  val domain = config.getString("messaging.domain")

  "A client" when {
    "usernames don't have domains " should {
      "connects to the xmpp server" in new TestFunctionsWithoutDomain {
        connected
      }

      "allows user registration and deletion" in new TestFunctionsWithoutDomain {
        registration
      }

      "enables users to chat to each other" in new TestFunctionsWithoutDomain {
        chat
      }

      "enables async chats (message recipient offline)" in new TestFunctionsWithoutDomain {
        asyncChat
      }

      "enables XEP-0066 file transfers" in new TestFunctionsWithoutDomain {
        XEP_0066_FileTransfers
      }

      "informs event listeners about chat partners becoming available / unavailable" in new TestFunctionsWithoutDomain {
        availability
      }

      "provides information about who is online and offline (roster)" in new TestFunctionsWithoutDomain {
        roster
      }

      "message receiver subscribes to sender" in new TestFunctionsWithoutDomain {
        receiver_connects
      }
    }

    "usernames have domains " should {
      "connects to the xmpp server"  in new TestFunctionsWithDomain {
        connected
      }

      "allows user registration" in new TestFunctionsWithDomain {
        registration
      }

      "enables users to chat to each other" in new TestFunctionsWithDomain {
        chat
      }

      "enables async chats (message recipient offline)" in new TestFunctionsWithDomain {
        asyncChat
      }

      "enables XEP-0066 file transfers" in new TestFunctionsWithDomain {
        XEP_0066_FileTransfers
      }

      "informs event listeners about chat partners becoming available / unavailable" in new TestFunctionsWithDomain {
        availability
      }

      "provides information about who is online and offline (roster)" in new TestFunctionsWithDomain {
        roster
      }

      "message receiver subscribes to sender" in new TestFunctionsWithDomain {
        receiver_connects
      }
    }
  }


  abstract class TestFunctions extends AnyRef with SharedFixture {
      def connected:Unit = {
        val connected = adminUser ? Connect(User(adminUsername, None), Password(adminPassword))
        connected.value.get shouldBe Success(Connected)
        adminUser ! Disconnect
      }

     def registration:Unit = {
       val username = randomUsername
       val userPass = Password(username.name)

       val connected = adminUser ? Connect(User(adminUsername, None), Password(adminPassword))
       adminUser ! RegisterUser(username, userPass)

       val connected1 = user1 ? Connect(username, userPass)
       connected1.value.get shouldBe Success(Connected)

       user1 ! DeleteUser
       val connected2 = user1 ? Connect(username, userPass)
       connected2.value.get.get match {
         case ConnectError(t) ⇒ //that's all we want to check
       }
     }

    def chat:Unit = {
      withTwoConnectedUsers {
        user1 ! SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)
      }
    }

    def asyncChat: Unit = {
      withTwoUsers {
        user1 ! Connect(username1, user1Pass)
        val user2Listener = newEventListener
        user2 ! RegisterEventListener(user2Listener.ref)

        user1 ! SendMessage(username2, testMessage)

        // yeah, sleeping is bad, but I dunno how else to make this guaranteed async.
        Thread.sleep(1000)
        user2 ! Connect(username2, user2Pass)

        verifyMessageArrived(user2Listener, username1, username2, testMessage)
      }
    }

    def XEP_0066_FileTransfers = {
      withTwoConnectedUsers {
        val fileUrl = "https://raw.githubusercontent.com/mpollmeier/gremlin-scala/master/README.md"
        val fileDescription = Some("file description")
        user1 ! SendFileMessage(username2, fileUrl, fileDescription)

        user2Listener.expectMsgPF(3 seconds, "xep-0066 file transfer") {
          case FileMessageReceived(chat, message, outOfBandData) ⇒
            chat.getParticipant should startWith(username1.name)
            message.getTo should startWith(username2.name)
            outOfBandData.url shouldBe fileUrl
            outOfBandData.desc shouldBe fileDescription
        }
      }
    }

    def availability = {
      withTwoConnectedUsers {
        user1 ! SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)
        user1Listener.fishForMessage(3 seconds, "notification that user2 came online") {
          case UserBecameAvailable(user) ⇒
            user.name should startWith(username2.name)
            true
        }

        user2 ! Disconnect
        user1Listener.fishForMessage(3 seconds, "notification that user2 went offline") {
          case UserBecameUnavailable(user) ⇒
            user.name should startWith(username2.name)
            true
        }

        user2 ! Connect(username2, user2Pass)
        user1Listener.fishForMessage(3 seconds, "notification that user2 came online") {
          case UserBecameAvailable(user) ⇒
            user.name should startWith(username2.name)
            true
        }
      }
    }

    def roster = {
      withTwoConnectedUsers {
        user1 ! SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)

        user1Listener.fishForMessage(3 seconds, "notification that user2 is in roster"){
          case UserBecameAvailable(user) =>
            val roster = getRoster(user1)
            roster.getEntries should have size 1
            val entry = roster.getEntries.head
            entry.getUser should startWith(username2.name)
            roster.getPresence(entry.getUser).getType shouldBe Presence.Type.available
            true
        }

        user2 ! Disconnect
        user1Listener.fishForMessage(3 seconds, "notification that user2 is not in roster") {
          case UserBecameUnavailable(user) =>
            val roster = getRoster(user1)
            roster.getEntries should have size 1
            val entry = roster.getEntries.head
            entry.getUser should startWith(username2.name)
            roster.getPresence(entry.getUser).getType shouldBe Presence.Type.unavailable
            true
        }
      }
    }

    def receiver_connects = {
      withTwoConnectedUsers {
       user1 ! SendMessage(username2, testMessage)
       verifyMessageArrived(user2Listener, username1, username2, testMessage)

        user2Listener.fishForMessage(3 seconds, "notification that user1 is in roster"){
          case UserBecameAvailable(user) =>
            val roster = getRoster(user2)
            roster.getEntries should have size 1
            val entry = roster.getEntries.head
            entry.getUser should startWith(username1.name)
            roster.getPresence(entry.getUser).getType shouldBe Presence.Type.available
            true
        }
      }
    }

    private def getRoster(u: TestActorRef[Nothing]): Roster = {
      val rosterFuture = (u ? GetRoster).mapTo[GetRosterResponse]
      Await.result(rosterFuture, 3 seconds).roster
    }
  }

  class TestFunctionsWithoutDomain extends TestFunctions with Fixture {

  }

  class TestFunctionsWithDomain extends TestFunctions with FixtureWithDomain{
    assert(username1.name.contains("@"))
    assert(username2.name.contains("@"))
  }

  trait SharedFixture {


    val username1:User
    val username2:User
    val user1Pass:Password
    val user2Pass:Password

    val adminUser = TestActorRef(Props[Client])
    val user1 = TestActorRef(Props[Client])
    val user2 = TestActorRef(Props[Client])


    val user1Listener = newEventListener
    val user2Listener = newEventListener

    val testMessage = "unique test message" + UUID.randomUUID

    def newEventListener: TestProbe = {
      val eventListener = TestProbe()
      eventListener.ignoreMsg {
        case MessageReceived(_, message) ⇒ message.getSubject == "Welcome!"
      }
      eventListener
    }

    def withTwoUsers(block: ⇒ Unit): Unit = {
      adminUser ! Connect(User(adminUsername, None), Password(adminPassword))
      adminUser ! RegisterUser(username1, Password(username1.name))
      adminUser ! RegisterUser(username2, Password(username2.name))

      user1 ! RegisterEventListener(user1Listener.ref)
      user2 ! RegisterEventListener(user2Listener.ref)
      try {
        block
      } finally {
        user1 ! DeleteUser
        user2 ! DeleteUser
      }
    }

    def withTwoConnectedUsers(block: ⇒ Unit): Unit =
      withTwoUsers {
        user1 ! Connect(username1, user1Pass)
        user2 ! Connect(username2, user2Pass)
        block
      }

    def verifyMessageArrived(testProbe: TestProbe, sender: User, recipient: User, messageBody: String): Unit = {
      testProbe.fishForMessage(3 seconds, "expected message to be delivered") {
        case MessageReceived(chat, message) ⇒
          chat.getParticipant should startWith(sender.name)
          message.getTo should startWith(recipient.name)
          message.getBody shouldBe messageBody
          true
        case _ ⇒ false
      }
    }
  }

  trait Fixture extends SharedFixture {
    override val username1 = randomUsername
    override val username2 = randomUsername
    override val user1Pass = Password(username1.name)
    override val user2Pass = Password(username2.name)

  }

  trait FixtureWithDomain extends SharedFixture {
    override val username1 = nameWithDomain(randomUsername)
    override val username2 = nameWithDomain(randomUsername)
    override val user1Pass = Password(username1.name)
    override val user2Pass = Password(username2.name)
  }

  def randomUsername = User(s"testuser-${UUID.randomUUID.toString.substring(9)}", None)
  def nameWithDomain(u: User) = User(u.name + s"@$domain", None)

  override def beforeEach() {
    system = ActorSystem()
  }

  override def afterEach() {
    system.shutdown()
  }
}

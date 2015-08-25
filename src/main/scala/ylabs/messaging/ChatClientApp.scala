package ylabs.messaging

import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import org.jivesoftware.smack.chat._
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import collection.JavaConversions._
import collection.mutable

object ChatClientApp extends App {
  import ChatClient.Messages._

  val system = ActorSystem()
  val chattie = system.actorOf(Props[ChatActor], "chatClient")

  var on = true
  computerSays("What now?")
  while (on) {
    io.StdIn.readLine match {
      case "connect" ⇒
        println("username: "); val username = io.StdIn.readLine
        val password = username
        // println("password: "); val password = io.StdIn.readLine
        // val username = "admin5"
        // val password = "admin5"
        chattie ! Connect(username, password)

      case "openchat" ⇒
        computerSays("who may i connect you with, sir?")
        val user = io.StdIn.readLine
        chattie ! ChatTo(user)

      case "message" ⇒
        computerSays("who do you want to send a message to, sir?")
        val user = io.StdIn.readLine
        computerSays("what's your message, sir?")
        val message = io.StdIn.readLine
        chattie ! SendMessage(user, message)

      case "leavechat" ⇒
        computerSays("who may i disconnect you from, sir?")
        val user = io.StdIn.readLine
        chattie ! LeaveChat(user)

      case "disconnect" ⇒
        chattie ! Disconnect

      case "exit" ⇒
        computerSays("shutting down")
        chattie ! Shutdown
        on = false

      case _ ⇒ computerSays("Que? No comprendo. Try again, sir!")
    }
  }

  def computerSays(s: String) = println(s">> $s")
}

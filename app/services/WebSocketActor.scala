package services

import akka.actor._
import akka.actor.FSM.Event
import play.api.libs.json.{Json, JsValue, JsError, JsSuccess}
import play.Logger
import scala.concurrent.duration._
import javax.inject._
import scala.util.{Success}
import play.api.Configuration
import models.User

// States in the FSM
sealed trait State
case object Unauthenticated extends State
case object Authenticated extends State

// State data
sealed trait Data
case object NoData extends Data
case class UserData(user: User) extends Data

class WebSocketActor (ws: ActorRef, userService: UserService)
  extends LoggingFSM[State, Data] {

  startWith(Unauthenticated, NoData)
  ws ! Json.toJson(AuthRequest(): Message)
  
  when(Unauthenticated) {
    case Event(json: JsValue, _) => {
      json.validate[Message] match {
        case s: JsSuccess[Message] => {
          s.get match {
            case Auth(token) => {
              userService.authenticate(token) match {
                case Some(user) => {
                  ws ! Json.toJson(Response(true, "Authentication success"))
                  goto(Authenticated) using UserData(user)
                }
                case None => {
                  ws ! Json.toJson(Response(false, "Authentication failure"))
                  stay // or die?
                }
              }
            }
            case _ => {
              val msg = "Invalid message at this state (Unauthenticated)"
              Logger.warn(msg)
              ws ! Json.toJson(Response(false, msg))
              stay // or die?
            }
          }
        }
        case e: JsError => {
          val msg = s"Could not validate json ($json) as Message"
          Logger.error(msg)
          ws ! Json.toJson(Response(false, msg))
          stay // or die?
        }

      }
    }
  }

  onTransition {
    case Unauthenticated -> Authenticated =>
      stateData match {
        case _ => // nothing to do
      }
  }

  when(Authenticated) {
    case Event(json: JsValue, _) =>
      json.validate[Message] match {
        case s: JsSuccess[Message] => {
          s.get match {
            case ADD_ITEM(id, contents) => {
              ws ! Json.toJson(Response(true, "added item"))
              stay
            }
            case EDIT_ITEM(id, contents) => {
              ws ! Json.toJson(Response(true, "edited item"))
              stay
            }
            case _ => {
              Logger.info("Unknown message")
              ws ! Json.toJson(Response(false, "Unknown message"))
              stay
            }
          }
        }
        case e: JsError => {
          Logger.error("Could not validate json as Message")
          ws ! Json.toJson(Response(false, "Invalid message"))
          stay
        }
      }
  }

  override def postStop() = {
    Logger.info("WS closed")
  }
}

@Singleton
class WebSocketActorProvider @Inject() (userService: UserService) {
  def props(ws: ActorRef) = Props(new WebSocketActor(ws, userService))
  def get(ws: ActorRef) = new WebSocketActor(ws, userService)
}

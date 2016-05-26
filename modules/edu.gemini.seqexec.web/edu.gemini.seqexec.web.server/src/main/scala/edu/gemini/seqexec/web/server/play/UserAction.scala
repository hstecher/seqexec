package edu.gemini.seqexec.web.server.play

import play.api.mvc._
import edu.gemini.seqexec.web.common.UserDetails
import edu.gemini.seqexec.web.server.security.{AuthenticationConfig, MissingCookie}
import edu.gemini.seqexec.web.server.security.AuthenticationService._

import scala.concurrent.Future
import scalaz._
import Scalaz._

case class UserRequest[A](user: Option[UserDetails], request: Request[A]) extends WrappedRequest[A](request)

object UserAction extends
    ActionBuilder[UserRequest] with ActionTransformer[Request, UserRequest] {
  override def transform[A](request: Request[A]) = Future.successful {

    def checkAuth(req: Request[A]): AuthResult =
      for {
        cookies <- \/-(req.cookies)
        token   <- cookies.find(_.name == AuthenticationConfig.cookieName) \/> MissingCookie
        auth    <- decodeToken(token.value)
      } yield auth

    checkAuth(request) match {
      case \/-(u) => new UserRequest(u.some, request)
      case -\/(e) => new UserRequest(none, request)
    }
  }
}
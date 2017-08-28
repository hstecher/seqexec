// Copyright (c) 2016-2017 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.seqexec.web.server.security

import edu.gemini.seqexec.model.UserDetails
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FlatSpec, Matchers}
import squants.time.Hours

import scalaz.\/-

class JWTTokensSpec extends FlatSpec with Matchers with PropertyChecks {
  val ldapConfig = LDAPConfig(Nil)
  val config = AuthenticationConfig(devMode = true, Hours(8), "token", "key", useSSL = false, ldapConfig)
  val authService = AuthenticationService(config)

  "JWT Tokens" should "encode/decode" in {
    forAll { (u: String, p: String) =>
      val userDetails = UserDetails(u, p)
      val token = authService.buildToken(userDetails).unsafePerformSync
      \/-(userDetails) shouldEqual authService.decodeToken(token)
    }
  }
}
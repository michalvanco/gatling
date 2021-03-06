/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.cache

import java.net.URI

import com.ning.http.client.Request
import com.ning.http.client.date.RFC2616DateParser
import com.typesafe.scalalogging.slf4j.StrictLogging

import io.gatling.core.session.{ Expression, Session, SessionPrivateAttributes }
import io.gatling.core.util.NumberHelper.extractLongValue
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.core.validation.SuccessWrapper
import io.gatling.http.{ HeaderNames, HeaderValues }
import io.gatling.http.ahc.JodaTimeConverter
import io.gatling.http.config.HttpProtocol
import io.gatling.http.response.Response

object CacheHandling extends StrictLogging {

  val httpExpireStoreAttributeName = SessionPrivateAttributes.privateAttributePrefix + "http.cache.expireStore"
  def getExpireStore(session: Session): Map[URI, Long] = session(httpExpireStoreAttributeName).asOption[Map[URI, Long]] match {
    case Some(store) => store
    case _           => Map.empty
  }
  def getExpire(httpProtocol: HttpProtocol, session: Session, uri: URI): Option[Long] = if (httpProtocol.requestPart.cache) getExpireStore(session).get(uri) else None
  def clearExpire(session: Session, uri: URI) = {
    logger.info(s"Resource $uri caching expired")
    session.set(httpExpireStoreAttributeName, getExpireStore(session) - uri)
  }

  val httpLastModifiedStoreAttributeName = SessionPrivateAttributes.privateAttributePrefix + "http.cache.lastModifiedStore"
  def getLastModifiedStore(session: Session): Map[URI, String] = session(httpLastModifiedStoreAttributeName).asOption[Map[URI, String]] match {
    case Some(store) => store
    case _           => Map.empty
  }
  def getLastModified(httpProtocol: HttpProtocol, session: Session, uri: URI): Option[String] = if (httpProtocol.requestPart.cache) getLastModifiedStore(session).get(uri) else None

  val httpEtagStoreAttributeName = SessionPrivateAttributes.privateAttributePrefix + "http.cache.etagStore"
  def getEtagStore(session: Session): Map[URI, String] = session(httpEtagStoreAttributeName).asOption[Map[URI, String]] match {
    case Some(store) => store
    case _           => Map.empty
  }
  def getEtag(httpProtocol: HttpProtocol, session: Session, uri: URI): Option[String] = if (httpProtocol.requestPart.cache) getEtagStore(session).get(uri) else None

  val maxAgePrefix = "max-age="
  val maxAgeZero = maxAgePrefix + "0"

  def extractExpiresValue(timestring: String): Option[Long] = {

      def removeQuote(s: String) =
        if (!s.isEmpty) {
          var start = 0
          var end = s.length

          if (s.charAt(0) == '"')
            start += 1

          if (s.charAt(s.length() - 1) == '"')
            end -= 1

          s.substring(start, end)
        } else
          s

    // FIXME use offset instead of 2 substrings
    val trimmedTimeString = removeQuote(timestring.trim)

    Option(new RFC2616DateParser(trimmedTimeString).parse).map(JodaTimeConverter.toTime)
  }

  def extractMaxAgeValue(s: String): Option[Long] = {
    val index = s.indexOf(maxAgePrefix)
    val start = maxAgePrefix.length + index
    if (index >= 0 && start <= s.length)
      s.charAt(start) match {
        case '-'            => Some(-1)
        case c if c.isDigit => Some(extractLongValue(s, start))
        case _              => None
      }
    else
      None
  }

  def getResponseExpires(httpProtocol: HttpProtocol, response: Response): Option[Long] = {
      def pragmaNoCache = response.header(HeaderNames.PRAGMA).exists(_.contains(HeaderValues.NO_CACHE))
      def cacheControlNoCache = response.header(HeaderNames.CACHE_CONTROL)
        .exists(h => h.contains(HeaderValues.NO_CACHE) || h.contains(HeaderValues.NO_STORE) || h.contains(maxAgeZero))
      def maxAgeAsExpiresValue = response.header(HeaderNames.CACHE_CONTROL).flatMap(extractMaxAgeValue).map { maxAge =>
        if (maxAge < 0)
          maxAge
        else
          maxAge * 1000 + nowMillis
      }
      def expiresValue = response.header(HeaderNames.EXPIRES).flatMap(extractExpiresValue).filter(_ > nowMillis)

    if (pragmaNoCache || cacheControlNoCache) {
      None
    } else {
      // If a response includes both an Expires header and a max-age directive, the max-age directive overrides the Expires header, 
      // even if the Expires header is more restrictive. (http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.3)
      maxAgeAsExpiresValue.orElse(expiresValue).filter(_ > 0)
    }

  }

  def cache(httpProtocol: HttpProtocol, session: Session, request: Request, response: Response): Session = {

    val uri = request.getURI

    val updateExpire = (session: Session) => getResponseExpires(httpProtocol, response) match {
      case Some(expires) =>
        logger.debug(s"Setting Expires $expires for uri $uri")
        val expireStore = getExpireStore(session)
        session.set(httpExpireStoreAttributeName, expireStore + (uri -> expires))

      case None => session
    }

    val updateLastModified = (session: Session) => response.header(HeaderNames.LAST_MODIFIED) match {
      case Some(lastModified) =>
        logger.debug(s"Setting LastModified $lastModified for uri $uri")
        val lastModifiedStore = getLastModifiedStore(session)
        session.set(httpLastModifiedStoreAttributeName, lastModifiedStore + (uri -> lastModified))

      case None => session
    }

    val updateEtag = (session: Session) => response.header(HeaderNames.ETAG) match {
      case Some(etag) =>
        logger.debug(s"Setting Etag $etag for uri $uri")
        val etagStore = getEtagStore(session)
        session.set(httpEtagStoreAttributeName, etagStore + (uri -> etag))

      case None => session
    }

    if (httpProtocol.requestPart.cache)
      (updateExpire andThen updateEtag andThen updateLastModified)(session)
    else
      session
  }

  val flushCache: Expression[Session] = _.removeAll(httpExpireStoreAttributeName, httpLastModifiedStoreAttributeName, httpEtagStoreAttributeName).success
}

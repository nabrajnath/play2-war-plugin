package play.core.server.servlet

import javax.servlet._
import javax.servlet.annotation._
import javax.servlet.http._
import java.io._
import java.util.Arrays

import play.api._
import play.api.mvc._
import play.api.http._
import play.api.http.HeaderNames._
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.libs.concurrent._
import play.core._
import play.core.server.servlet._
import server.Server

import scala.collection.JavaConverters._

object Servlet30Wrapper {
  var playServer: Play2WarServer = null
  var servletContext: ServletContext = null
}

@WebServlet(name = "Play", urlPatterns = Array { "/" }, asyncSupported = true)
@WebListener
class Servlet30Wrapper extends HttpServlet with ServletContextListener with Helpers {

  protected override def service(servletRequest: HttpServletRequest, servletResponse: HttpServletResponse) = {
    trace("HTTP request received: " + servletRequest)

    val aSyncContext = servletRequest.startAsync

    // Disable timeout for long-polling
    aSyncContext.setTimeout(-1)

    val server = Servlet30Wrapper.playServer

    //    val keepAlive -> non-sens
    //    val websocketableRequest -> non-sens
    val version = servletRequest.getProtocol.substring("HTTP/".length, servletRequest.getProtocol.length)
    val servletUri = servletRequest.getServletPath
    val parameters = Map.empty[String, Seq[String]] ++ servletRequest.getParameterMap.asScala.mapValues(Arrays.asList(_: _*).asScala)
    val rHeaders = getPlayHeaders(servletRequest)
    val rCookies = getPlayCookies(servletRequest)
    val httpMethod = servletRequest.getMethod

    val requestHeader = new RequestHeader {
      def uri = servletUri
      def path = uri
      def method = httpMethod
      def queryString = parameters
      def headers = rHeaders
      def username = None

      override def toString = {
        super.toString + "\nPath: " + path + "\nParameters: " + queryString + "\nHeaders: " + headers + "\nCookies: " + rCookies
      }
    }
    trace("HTTP request content: " + requestHeader)

    // converting servlet response to play's
    val response = new Response {

      def handle(result: Result) {

        aSyncContext.getResponse match {

          // Handle only HttpServletResponse
          case httpResponse: HttpServletResponse => {

            result match {

              case AsyncResult(p) => p.extend1 {
                case Redeemed(v) => handle(v)
                case Thrown(e) => {
                  error("Waiting for a promise, but got an error: " + e.getMessage, e)
                  handle(Results.InternalServerError)
                }
              }

              case r @ SimpleResult(ResponseHeader(status, headers), body) => {
                trace("Sending simple result: " + r)

                httpResponse.setStatus(status)

                // Set response headers
                headers.filterNot(_ == (CONTENT_LENGTH, "-1")).foreach {

                  case (name @ play.api.http.HeaderNames.SET_COOKIE, value) => {
                    getServletCookies(value).map {
                      c => httpResponse.addCookie(c)
                    }
                  }

                  case (name, value) => httpResponse.setHeader(name, value)
                }

                // Stream the result
                headers.get(CONTENT_LENGTH).map { contentLength =>
                  trace("Result with Content-length: " + contentLength)

                  val writer: Function1[r.BODY_CONTENT, Promise[Unit]] = x => {
                    Promise.pure(
                      {
                        aSyncContext.getResponse.getOutputStream.write(r.writeable.transform(x))
                        aSyncContext.getResponse.getOutputStream.flush
                      }).extend1 { case Redeemed(()) => (); case Thrown(ex) => debug(ex.toString) }
                  }

                  val bodyIteratee = {
                    val writeIteratee = Iteratee.fold1(
                      Promise.pure(()))((_, e: r.BODY_CONTENT) => writer(e))

                    writeIteratee.mapDone { _ =>
                      aSyncContext.complete()
                    }
                  }

                  body(bodyIteratee)
                }.getOrElse {
                  trace("Result without Content-length")

                  // No Content-Length header specified, buffer in-memory
                  val byteBuffer = new ByteArrayOutputStream
                  val writer: Function2[ByteArrayOutputStream, r.BODY_CONTENT, Unit] = (b, x) => b.write(r.writeable.transform(x))
                  val stringIteratee = Iteratee.fold(byteBuffer)((b, e: r.BODY_CONTENT) => { writer(b, e); b })
                  val p = body |>> stringIteratee

                  p.flatMap(i => i.run)
                    .onRedeem { buffer =>
                      trace("Buffer size to send: " + buffer.size)
                      aSyncContext.getResponse.setContentLength(buffer.size)
                      aSyncContext.getResponse.getOutputStream.flush
                      buffer.writeTo(aSyncContext.getResponse.getOutputStream)
                      aSyncContext.complete()
                    }
                }
              }

              case r @ ChunkedResult(ResponseHeader(status, headers), chunks) => {
                trace("Sending chunked result: " + r)

                httpResponse.setStatus(status)

                // Copy headers to netty response
                headers.foreach {

                  case (name @ play.api.http.HeaderNames.SET_COOKIE, value) => {
                    getServletCookies(value).map {
                      c => httpResponse.addCookie(c)
                    }
                  }

                  case (name, value) => httpResponse.setHeader(name, value)
                }

                val writer: Function1[r.BODY_CONTENT, Promise[Unit]] = x => {
                  Promise.pure(
                    {
                      aSyncContext.getResponse.getOutputStream.write(r.writeable.transform(x))
                      aSyncContext.getResponse.getOutputStream.flush
                    }).extend1 { case Redeemed(()) => (); case Thrown(ex) => debug(ex.toString) }
                }

                val chunksIteratee = {
                  val writeIteratee = Iteratee.fold1(
                    Promise.pure(()))((_, e: r.BODY_CONTENT) => writer(e))

                  writeIteratee.mapDone { _ =>
                    aSyncContext.complete()
                  }
                }

                chunks(chunksIteratee)
              }

              case defaultResponse @ _ =>
                trace("Default response: " + defaultResponse)
                error("Unhandle default response: " + defaultResponse)

                httpResponse.setContentLength(0);
                httpResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                aSyncContext.complete()
            } // end match result

          } // end case HttpServletResponse

          case unexpected => error("Oops, unexpected message received in Play server (please report this problem): " + unexpected)

        } // end match getResponse
      } // end handle method
    }

    // get handler for request
    val handler = server.getHandlerFor(requestHeader)

    handler match {

      //execute normal action
      case Right((action: Action[_], app)) => {

        trace("Serving this request with: " + action)

        val bodyParser = action.parser

        val eventuallyBodyParser = server.getBodyParser[action.BODY_CONTENT](requestHeader, bodyParser)

        val eventuallyResultOrBody =
          eventuallyBodyParser.flatMap { bodyParser =>

            requestHeader.headers.get("Expect") match {
              case Some("100-continue") => {
                bodyParser.fold(
                  (_, _) => Promise.pure(()),
                  k => {
                    //                        val continue = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)
                    //                        e.getChannel.write(continue)
                    Promise.pure(())
                  },
                  (_, _) => Promise.pure(()))

              }
              case _ => trace("Expect header:" + requestHeader.headers.get("Expect"))
            }

            lazy val bodyEnumerator = {
              val body = Stream.continually(aSyncContext.getRequest.getInputStream.read).takeWhile(-1 !=).map(_.toByte).toArray
              Enumerator(body).andThen(Enumerator.enumInput(EOF))
            }

            try {
              (bodyEnumerator |>> bodyParser): Promise[Iteratee[Array[Byte], Either[Result, action.BODY_CONTENT]]]
            } finally {
              aSyncContext.getRequest.getInputStream.close
            }
            //                            }
          }

        val eventuallyResultOrRequest =
          eventuallyResultOrBody
            .flatMap(it => it.run)
            .map {
              _.right.map(b =>
                new Request[action.BODY_CONTENT] {
                  def uri = servletUri
                  def path = servletUri
                  def method = httpMethod
                  def queryString = parameters
                  def headers = rHeaders
                  def username = None
                  val body = b
                })
            }

        eventuallyResultOrRequest.extend(_.value match {
          case Redeemed(Left(result)) => {
            trace("Got direct result from the BodyParser: " + result)
            response.handle(result)
          }
          case Redeemed(Right(request)) => {
            trace("Invoking action with request: " + request)
            server.invoke(request, response, action.asInstanceOf[Action[action.BODY_CONTENT]], app)
          }
          case error => {
            this.error("Cannot invoke the action, eventually got an error: " + error)
            response.handle(Results.InternalServerError)
          }
        })

      }

      //handle websocket action
      case Right((ws @ WebSocket(f), app)) => {
        error("Impossible to serve Web Socket request:" + ws)
        response.handle(Results.InternalServerError)
      }

      case unexpected => {
        error("Oops, unexpected message received in Play server (please report this problem): " + unexpected)
        response.handle(Results.InternalServerError)
      }
    }

  }

  override def contextInitialized(e: ServletContextEvent) = {
    import play.core.server.servlet.Servlet30Wrapper._

    servletContext = e.getServletContext

    servletContext.log("PlayServletWrapper > contextInitialized")
    
    servletContext.log("PlayServletWrapper > contextInitialized (2)")

//    Logger.configure(Map.empty, Map.empty, Mode.Prod)
    
    servletContext.log("PlayServletWrapper > contextInitialized (3)")

    val classLoader = this.getClass.getClassLoader;

    playServer = new Play2WarServer(new WarApplication(classLoader, Mode.Prod))
    
    servletContext.log("PlayServletWrapper > contextInitialized (4)")
  }

  override def contextDestroyed(e: ServletContextEvent) = {
    e.getServletContext.log("PlayServletWrapper > contextDestroyed")

    stopPlayServer(e.getServletContext)
  }

  override def destroy = {
    getServletContext.log("PlayServletWrapper > destroy")

    stopPlayServer(getServletContext)
  }

  private def stopPlayServer(sc: ServletContext) = {
    Option(Servlet30Wrapper.playServer).map {
      s =>
        s.stop()
        Servlet30Wrapper.playServer = null
        sc.log("Play server stopped")
    } // if playServer is null, nothing to do
  }

  private def trace(message: => String) {
    Logger("play").trace(message)
    Servlet30Wrapper.servletContext.log("[TRACE] " + message)
  }

  private def debug(message: => String) {
    Logger("play").debug(message)
    Servlet30Wrapper.servletContext.log("[DEBUG] " + message)
  }

  private def info(message: => String) {
    Logger("play").info(message)
    Servlet30Wrapper.servletContext.log("[INFO]  " + message)
  }

  private def error(message: => String) {
    Logger("play").error(message)
    Servlet30Wrapper.servletContext.log("[ERROR] " + message)
  }

  private def error(message: => String, error: => Throwable) {
    Logger("play").error(message, error)
    Servlet30Wrapper.servletContext.log("[ERROR] " + message + "\n" + error)
  }

  private def warn(message: => String) {
    Logger("play").warn(message)
    Servlet30Wrapper.servletContext.log("[WARN]  " + message)
  }
}
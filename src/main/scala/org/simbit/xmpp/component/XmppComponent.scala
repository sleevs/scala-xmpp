package org.simbit.xmpp
{
	package component
	{
		import java.io._
		import java.util._
		
		import java.io.IOException
		
		import java.net.Socket
		import java.net.InetSocketAddress
		
		import scala.collection._
		import scala.xml._
		
		import net.lag.naggati.IoHandlerActorAdapter
		import net.lag.naggati.MinaMessage
		import net.lag.naggati.ProtocolError
					
		import org.apache.mina.core.session.IoSession
		import org.apache.mina.filter.codec.ProtocolCodecFilter
		import org.apache.mina.filter.logging.LoggingFilter
		import org.apache.mina.transport.socket.nio.NioSocketConnector
		
		//import scala.actors.Actor
		//import scala.actors.Actor._
		import com.twitter.actors.Actor
		import com.twitter.actors.Actor._
		
		import org.simbit.util._
		
		import org.simbit.xmpp.codec._
		import org.simbit.xmpp.protocol._
		import org.simbit.xmpp.protocol.presence._		
		import org.simbit.xmpp.protocol.iq._
		import org.simbit.xmpp.protocol.message._
		import org.simbit.xmpp.protocol.extensions._
		
		import org.simbit.xmpp.protocol.Protocol._
		
		object XmppComponent
		{
			val DEFAULT_TIMEOUT = 5 * 1000
		}
		
		trait XmppComponent extends Logger
		{
			protected val identities:Seq[disco.Identity] = Nil		
			protected val features:Seq[disco.Feature] = Nil
			// TODO, not sure if i like this, perhaps need to leave it up to the component to decide if and when it wants to register the extension builders
			protected val extensionsBuilders:Seq[ExtensionBuilder[_ <: Extension]] = Nil
			
			private var _jid:JID = _
			def jid = _jid
			
			private var _subdomain:String = _
			def subdomain = _subdomain
			
			private var _host:String = _
			def host = _host
			
			private var _port:Int = _
			def port = _port
			
			private var _secret:String = _
			def secret = _secret
			
			private var _timeout:Int = _
			def timeout = _timeout
						
			private var _connector:NioSocketConnector = _
			private var _session:IoSession = _
			
			def configure(subdomain:String, host:String, port:Int, secret:String, timeout:Int=0)
			{
				_jid = JID(null, subdomain + "." + host, null)
				_subdomain = subdomain
				_host = host
				_port = port
				_secret = secret
				_timeout = if (null == timeout) timeout else XmppComponent.DEFAULT_TIMEOUT
								
				require(this.subdomain != null)
				require(this.host != null)
				require(this.port != 0)
				require(this.secret != null)
				require(this.timeout != 0)
				
				// TODO, not sure if i like this, perhaps need to leave it up to the component to decide if and when it wants to register the extension builders
				extensionsBuilders.foreach( builder => ExtensionsManager.registerBuilder(builder) )
			}
			
			def start
			{
				start(this.subdomain, this.host, this.port, this.secret, this.timeout)
			}
			
			def start(subdomain:String, host:String, port:Int, secret:String, timeout:Int=0)
			{
				configure(subdomain, host, port, secret, timeout)
								
				// start a mina thread pool listening on server messages
				_connector = new NioSocketConnector()
				_connector.setConnectTimeout(timeout)
				_connector.getFilterChain.addLast("codec", new ProtocolCodecFilter(Codec.encoder, Codec.decoder))
				_connector.getFilterChain.addLast("logger", new LoggingFilter())
				_connector.setHandler(new IoHandlerActorAdapter(session => new XmppHandler(session, this.subdomain, this.secret, this.handleStanza)))
				
				val future = _connector.connect(new InetSocketAddress(host, port))
				future.awaitUninterruptibly
				_session = future.getSession
								
				// FIXME: interval should come from a configuration file
				ScheduledJobsManager.registerJob("keeplalive_" + this.subdomain, this.keepalive, 60 * 1000)
				ScheduledJobsManager.registerJob("cleanup_" + this.subdomain, this.cleanup, 10 * 60 * 1000)				
				ScheduledJobsManager.startAll
					
				// TODO: add hook for implementations
			}
			
			def shutdown
			{
				try
				{
					send(StreamTail())
					if (null != _session) _session.getCloseFuture.awaitUninterruptibly
					if (null != _connector) _connector.dispose
					
					ScheduledJobsManager.stopAll
				}
				catch
				{
					case e:Exception => error("disconnect error " + e) // TODO: do something intelligent here
				}
				finally
				{
					_session = null
					_connector = null
				}
			}
			
			def send(content:String) 
			{
				if (null == _session) return
				
				try 
				{
					_session.write(content)
				}
				catch
				{
					case e:Exception =>
					{
						// TODO: do something more intelligent here
						error(e)
						//if (!shutdown) reconnect
					}
				}
			}
						
			protected def handleStanza(stanza:Stanza) 
			{
				//TODO, find a better way to do the disco match, ideally we would match on a well defined disco instead of matching on the extension
				stanza match
			 	{
					case presence:Presence => handlePresence(presence)	
					case get @ Get(_, _, _, Some(info:disco.Info)) => handleDiscoInfo(get, info)
					case get @ Get(_, _, _, Some(items:disco.Items)) => handleDiscoItems(get, items)
					case iq:IQ => handleIQ(iq)
					case message:Message => handleMessage(message)
				}
			}
			
			protected def handlePresence(presence:Presence)
			{	
				// to be implemented by subclasses as required
			}

		    protected def handleIQ(iq:IQ)
			{    	
		    	// to be implemented by subclasses as required
			}
				
			protected def handleMessage(message:Message)
			{
				// to be implemented by subclasses as required
			}

			private def handleDiscoInfo(request:Get, infoRequest:disco.Info)
			{				
				request.to match
				{
					case Some(jid) if jid == this.jid => 
					{
						send(request.result(infoRequest.result(this.identities, this.features)))
					}
					case _ => getChildDiscoInfo(request.to.get, infoRequest) match
					{
						case Some(info) => send(request.result(info))
						case _ => // do nothing					
					}
				}
			}
			
			// to be implemented by sub classes as required
			protected def getChildDiscoInfo(jid:JID, request:disco.Info):Option[disco.InfoResult] = None
			
			private def handleDiscoItems(request:Get, itemsRequest:disco.Items)
			{				
				request.to match
				{
					case Some(jid) if jid == this.jid => getDiscoItems(itemsRequest) match 
					{
						case Some(items) => send(request.result(items))
						case _ => // do nothing						
					}
					case _ => getChildDiscoItems(request.to.get, itemsRequest) match
					{
						case Some(items) => send(request.result(items))
						case _ => // do nothing
					}
				}
			}
			
			// to be implemented by sub classes as required	
			protected def getDiscoItems(request:disco.Items):Option[disco.ItemsResult] = None
			
			// to be implemented by sub classes as required	
			protected def getChildDiscoItems(jid:JID, request:disco.Items):Option[disco.ItemsResult] = None
			
			protected def cleanup()
			{
				// to be implemented by sub classes as required
			}
			
			private def keepalive()
			{
				send(" ")
			}
		}
				
		private class XmppHandler(val session:IoSession, val subdomain:String, val secret:String, val stanzaHandler:(Stanza) => Unit) extends Actor with Logger
		{
			//session.getConfig.setReadBufferSize(2048)
			IoHandlerActorAdapter.filter(session) -= classOf[MinaMessage.MessageSent]
			IoHandlerActorAdapter.filter(session) -= classOf[MinaMessage.SessionIdle]
			
			start
			
			def act = 
			{
				loop 
				{
					react 
					{	
						case MinaMessage.SessionOpened => 
						{
							// open xmpp stream
							val head = StreamHead("jabber:component:accept", immutable.List("to" -> this.subdomain))
							send(head)
						}
						case MinaMessage.MessageReceived(message) => 
						{
							handle(message)
						}
						case MinaMessage.SessionClosed => 
						{
							// FIXME: should we try to re-connect?
							exit
						}
						case MinaMessage.ExceptionCaught(cause) => 
						{
							// TODO, do something more intelligent here
							error(this.subdomain + " mina exception caught: " + cause.getCause)
							//session.close
						}
					}
				}
			}
			
  			private def handle(message:AnyRef) = 
			{
				message match
				{
  					case head:StreamHead =>
					{
						head.findAttribute("id") match
						{
							case Some(connectionId) => send(ComponentHandshake(connectionId, secret))
							// TODO, do something more intelligent here
							case None => error(this.subdomain + " received an invaild stream head, conection id not found")
						}
					}
					// TODO, do something more intelligent here?
					case tail:StreamTail => debug(this.subdomain + " stream tail")
					// TODO, do something more intelligent here?
					case handshake:Handshake => info(this.subdomain + " connected to xmpp server")
					// TODO, do something more intelligent here
					case error:StreamError => debug(this.subdomain + " received stream error " + error)			
					case stanza:Stanza => handleStanza(stanza)
					case stanzas:Seq[Stanza] => stanzas.foreach( stanza => handleStanza(stanza) )	
				}
			}
  			
  			private def handleStanza(stanza:Stanza)
  			{
  				debug(stanza.to.getOrElse(this.subdomain) + " recieved stanza from: " + stanza.from.getOrElse("unknown"))
				
				try
				{
					stanzaHandler(stanza)
				}
				catch
				{
					// TODO, do something more intelligent here
					case e:Exception => error(this.subdomain + " failed handling stanza " + e + "\n" + stanza)
				}
  			}
			
			private def send(content:String) = 
			{
				session.write(content)
			}
		}
				
	}
}
package org.xmpp
{
	package protocol.extensions.muc
	{
		import scala.collection._
		import scala.xml._
		
		import org.xmpp.protocol._
		import org.xmpp.protocol.presence._
		import org.xmpp.protocol.extensions._
		
		import org.xmpp.protocol.Protocol._		
				
		object LeaveRoomResult
		{
			def apply(affiliation:Affiliation.Value, role:Role.Value, statuses:Option[Seq[Int]]):LeaveRoomResult =
			{
				val children = mutable.ListBuffer[Node]()
				children += <item affiliation={  affiliation.toString } role={ role.toString }  />
				if (!statuses.isEmpty) statuses.foreach ( status => children += <status code={ status.toString } /> )
				return apply(Builder.build(children))
			}
				
			def apply(xml:Node):LeaveRoomResult = LeaveRoomResult(xml)			
		}
		
		class LeaveRoomResult(xml:Node) extends X(xml)
		{				
			val affiliation:Affiliation.Value = Affiliation.withName((this.xml \ "@affiliation").text)
			
			val role:Role.Value = Role.withName((this.xml \ "@role").text)

			val statuses:Option[Seq[Int]] = 
			{
				val nodes = (xml \ "status")
				nodes.length match
				{
					case 0 => None
					case _ => Some(nodes.map ( node => node.text.toInt ))
				}
			}
		}
		
		
		
	}	
}
package org.hornetq.tests.integration.client;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.hornetq.core.client.ClientMessage;
import org.hornetq.core.client.ClientProducer;
import org.hornetq.core.client.ClientSession;
import org.hornetq.core.client.ClientSessionFactory;
import org.hornetq.core.client.ClientSessionFactoryImpl;
import org.hornetq.core.client.SendAcknowledgementHandler;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.ConfigurationImpl;
import org.hornetq.core.config.TransportConfiguration;
import org.hornetq.core.exception.HornetQException;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.message.Message;
import org.hornetq.core.remoting.Interceptor;
import org.hornetq.core.remoting.Packet;
import org.hornetq.core.remoting.RemotingConnection;
import org.hornetq.core.remoting.impl.invm.InVMAcceptorFactory;
import org.hornetq.core.remoting.impl.invm.InVMConnectorFactory;
import org.hornetq.core.remoting.impl.wireformat.PacketImpl;
import org.hornetq.core.server.HornetQ;
import org.hornetq.core.server.HornetQServer;

/**
 * 
 * From https://jira.jboss.org/jira/browse/HORNETQ-144
 * 
 */
public class HornetQCrashTest extends TestCase
{
   private static final Logger log = Logger.getLogger(HornetQCrashTest.class);

   public HornetQServer server;

   private volatile boolean ackReceived;

   public void testHang() throws Exception
   {
      Configuration configuration = new ConfigurationImpl();
      configuration.setPersistenceEnabled(false);
      configuration.setSecurityEnabled(false);
      configuration.getAcceptorConfigurations().add(new TransportConfiguration(InVMAcceptorFactory.class.getName()));

      server = HornetQ.newHornetQServer(configuration);

      server.start();

      server.getRemotingService().addInterceptor(new AckInterceptor(server));

      ClientSessionFactory clientSessionFactory = new ClientSessionFactoryImpl(new TransportConfiguration(InVMConnectorFactory.class.getName()));

      // Force an ack at once - this means the send call will block
      clientSessionFactory.setConfirmationWindowSize(1);

      ClientSession session = clientSessionFactory.createSession();

      session.setSendAcknowledgementHandler(new SendAcknowledgementHandler()
      {
         public void sendAcknowledged(final Message message)
         {
            ackReceived = true;
         }
      });

      ClientProducer producer = session.createProducer("fooQueue");

      ClientMessage msg = session.createMessage(false);

      msg.putStringProperty("someKey", "someValue");

      producer.send(msg);

      Thread.sleep(250);

      Assert.assertFalse(ackReceived);

      session.close();
   }

   public static class AckInterceptor implements Interceptor
   {
      private final HornetQServer server;

      AckInterceptor(final HornetQServer server)
      {
         this.server = server;
      }

      public boolean intercept(final Packet packet, final RemotingConnection connection) throws HornetQException
      {
         HornetQCrashTest.log.info("AckInterceptor.intercept " + packet);

         if (packet.getType() == PacketImpl.SESS_SEND)
         {
            try
            {
               HornetQCrashTest.log.info("Stopping server");

               // Stop the server when a message arrives, to simulate a crash
               server.stop();
            }
            catch (Exception e)
            {
               e.printStackTrace();
            }

            return false;
         }
         return true;
      }

   }

   @Override
   protected void tearDown() throws Exception
   {
      super.tearDown();

      server = null;
   }
}

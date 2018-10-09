package org.glowroot.agent.plugin.axis.v.one;

import javax.xml.namespace.QName;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class AxisAspect {

	@Shim("org.apache.axis.client.Call")
    public interface Call {
        String getTargetEndpointAddress();
        QName getOperationName();
    }

	private static class TraceEntryOrTimer {

        private final @Nullable TraceEntry traceEntry;
        private final @Nullable Timer timer;

        private TraceEntryOrTimer(final TraceEntry traceEntry) {
            this.traceEntry = traceEntry;
            timer = null;
        }

        private TraceEntryOrTimer(final Timer timer) {
            this.timer = timer;
            traceEntry = null;
        }

        private void onReturn() {
            if (traceEntry != null) {
                traceEntry.end();
            } else if (timer != null) {
                timer.stop();
            }
        }

        private void onThrow(final Throwable t) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            } else if (timer != null) {
                timer.stop();
            }
        }
    }


	@Pointcut(className = "org.apache.axis.client.Call", methodName = "invoke", methodParameterTypes = {}, timerName = "axis service")
	public static class ResourceAdvice {

		private static final TimerName timerName = Agent.getTimerName(ResourceAdvice.class);

		@OnBefore
		public static @Nullable TraceEntryOrTimer onBefore(final ThreadContext context, @BindReceiver final Call call) {
			String transactionName = call.getTargetEndpointAddress() + "#" + call.getOperationName().getLocalPart();
			context.setTransactionName(transactionName, Priority.CORE_PLUGIN);
			TraceEntry traceEntry = context.startServiceCallEntry("HTTP", transactionName, MessageSupplier.create("axis service: {}.{}()",
					call.getTargetEndpointAddress(), call.getOperationName().getLocalPart()), timerName);
			return new TraceEntryOrTimer(traceEntry);
		}

		@OnReturn
		public static void onReturn(@BindTraveler @Nullable final TraceEntryOrTimer entryOrTimer) {
			entryOrTimer.onReturn();
		}

		@OnThrow
		public static void onThrow(@BindThrowable final Throwable t, @BindTraveler @Nullable final TraceEntryOrTimer entryOrTimer) {
			entryOrTimer.onThrow(t);
		}
	}
}

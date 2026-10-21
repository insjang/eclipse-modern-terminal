import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public class RunTests {
	public static void main(String[] a) {
		LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request();
		for (String c : a) b.selectors(selectClass(c));
		SummaryGeneratingListener l = new SummaryGeneratingListener();
		LauncherFactory.create().execute(b.build(), l);
		l.getSummary().printTo(new java.io.PrintWriter(System.out));
		l.getSummary().printFailuresTo(new java.io.PrintWriter(System.out), 8);
		System.exit(l.getSummary().getTotalFailureCount() == 0 ? 0 : 1);
	}
}

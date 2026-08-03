package samples;

/**
 * Designed for the diff highlighting. On each loop iteration this program
 * mutates existing state and allocates something NEW.
 *
 * <p>Set a breakpoint at the marked line and repeatedly <b>Resume</b> (F8). Each
 * suspend the view highlights the variables that changed since the previous one:
 * {@code counter}, the array element, and the retargeted {@code held} local as
 * UPDATED, and the fresh box's rows as NEW. The dropped box simply disappears —
 * removals are not tracked.
 */
public class MutationOverTimeDemo {

	static final class Box {
		int payload;

		Box(int payload) {
			this.payload = payload;
		}
	}

	static int counter = 0;

	public static void main(String[] args) {
		int[] running = new int[4];
		Box held = new Box(0);

		for (int i = 1; i <= 8; i++) {
			counter += i;                 // UPDATED: static counter
			running[i % running.length] = counter; // UPDATED: one array cell
			Box fresh = new Box(counter); // NEW: a fresh object each iteration
			held = fresh;                 // UPDATED: held retargets; the old box just disappears
			System.out.println("i=" + i + " held=" + held.payload); // breakpoint here; Resume repeatedly
		}
	}
}

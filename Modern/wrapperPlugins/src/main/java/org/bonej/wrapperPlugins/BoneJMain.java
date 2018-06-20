
package org.bonej.wrapperPlugins;

import net.imagej.ImageJ;
import org.scijava.Gateway;

import java.io.IOException;

/**
 * A main class for quickly testing the wrapper plugins
 *
 * @author Richard Domander
 */
public class BoneJMain {

	public static void main(String... args) throws IOException {
		final Gateway imageJ = new ImageJ();
		imageJ.launch(args);
	}
}

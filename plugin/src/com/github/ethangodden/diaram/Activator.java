package com.github.ethangodden.diaram;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * The activator class controls the plug-in life cycle
 */
public class Activator extends AbstractUIPlugin {

	// The plug-in ID
	public static final String PLUGIN_ID = "diaram"; //$NON-NLS-1$

	// The shared instance
	private static @Nullable Activator plugin;
	
	/**
	 * The constructor
	 */
	public Activator() {
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

	/**
	 * Returns the shared instance, or null before {@link #start} / after {@link #stop}.
	 *
	 * @return the shared instance
	 */
	public static @Nullable Activator getDefault() {
		return plugin;
	}

}

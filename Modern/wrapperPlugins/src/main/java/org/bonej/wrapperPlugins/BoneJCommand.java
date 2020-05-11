package org.bonej.wrapperPlugins;

import java.util.List;

import net.imagej.ImgPlus;
import net.imagej.ops.OpService;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.ComplexType;

import org.bonej.wrapperPlugins.wrapperUtils.Common;
import org.bonej.wrapperPlugins.wrapperUtils.HyperstackUtils;
import org.bonej.wrapperPlugins.wrapperUtils.HyperstackUtils.Subspace;
import org.bonej.wrapperPlugins.wrapperUtils.UsageReporter;
import org.scijava.command.CommandService;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.PluginService;
import org.scijava.prefs.PrefService;

import static java.util.stream.Collectors.toList;

public abstract class BoneJCommand extends ContextCommand {
    private static UsageReporter reporter;
    protected List<Subspace<BitType>> subspaces;

    protected <C extends ComplexType<C>> List<Subspace<BitType>> find3DSubspaces(
            final ImgPlus<C> image) {
        final OpService opService = context().getService(OpService.class);
        final ImgPlus<BitType> bitImgPlus = Common.toBitTypeImgPlus(opService, image);
        return HyperstackUtils.split3DSubspaces(bitImgPlus).collect(toList());
    }

    protected void reportUsage() {
        if (reporter == null) {
            initReporter();
        }
        reporter.reportEvent(getClass().getName());
    }

    private void initReporter() {
        final PrefService prefService = context().getService(PrefService.class);
        final PluginService pluginService = context().getService(PluginService.class);
        final CommandService commandService = context().getService(CommandService.class);
        final LogService logService = context().getService(LogService.class);
        reporter = UsageReporter.getInstance(prefService, pluginService, commandService, logService);
    }

    static void setReporter(final UsageReporter reporter) {
        BoneJCommand.reporter = reporter;
    }
}

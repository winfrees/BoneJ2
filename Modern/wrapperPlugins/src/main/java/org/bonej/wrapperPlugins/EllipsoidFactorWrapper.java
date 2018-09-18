package org.bonej.wrapperPlugins;

import net.imagej.ImgPlus;
import net.imagej.display.ColorTables;
import net.imagej.ops.OpService;
import net.imagej.units.UnitService;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.binary.Thresholder;
import net.imglib2.algorithm.neighborhood.HyperSphereShape;
import net.imglib2.algorithm.neighborhood.Shape;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.bonej.ops.ellipsoid.Ellipsoid;
import org.bonej.ops.ellipsoid.FindEllipsoidFromBoundaryPoints;
import org.bonej.utilities.AxisUtils;
import org.bonej.utilities.ElementUtil;
import org.bonej.wrapperPlugins.wrapperUtils.Common;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.UIService;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static net.imglib2.roi.Regions.countTrue;
import static org.bonej.utilities.AxisUtils.getSpatialUnit;
import static org.bonej.utilities.Streamers.spatialAxisStream;
import static org.bonej.wrapperPlugins.CommonMessages.*;
import static org.scijava.ui.DialogPrompt.MessageType.WARNING_MESSAGE;
import static org.scijava.ui.DialogPrompt.OptionType.OK_CANCEL_OPTION;
import static org.scijava.ui.DialogPrompt.Result.OK_OPTION;

/**
 * Ellipsoid Factor
 * <p>
 * Ellipsoid
 * </p>
 *
 * @author Alessandro Felder
 */

@Plugin(type = Command.class, menuPath = "Plugins>BoneJ>Ellipsoid Factor 2")
public class EllipsoidFactorWrapper<R extends RealType<R> & NativeType<R>> extends ContextCommand {

    private final FindEllipsoidFromBoundaryPoints findLocalEllipsoidOp = new FindEllipsoidFromBoundaryPoints();

    @SuppressWarnings("unused")
    @Parameter(validater = "validateImage")
    private ImgPlus<R> inputImage;

    @Parameter(persist = false, required = false)
    private DoubleType sigma = new DoubleType(0);

    @Parameter(persist = false, required = false)
    private DoubleType percentageOfRidgePoints = new DoubleType(0.8);

    @Parameter(label = "Ridge image", type = ItemIO.OUTPUT)
    private ImgPlus<UnsignedByteType> ridgePointsImage;

    @Parameter(label = "EF image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> efImage;

    @Parameter(label = "ID image", type = ItemIO.OUTPUT)
    private ImgPlus<IntType> eIdImage;

    @Parameter(label = "Volume Image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> vImage;

    @Parameter(label = "a/b Image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> aToBAxisRatioImage;

    @Parameter(label = "b/c Image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> bToCAxisRatioImage;

    @Parameter(label = "Unweighted Flinn Plot", type = ItemIO.OUTPUT)
    private ImgPlus<BitType> flinnPlotImage;

    @Parameter(label = "Flinn Peak Plot", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> flinnPeakPlotImage;

    @SuppressWarnings("unused")
    @Parameter
    private OpService opService;

    @SuppressWarnings("unused")
    @Parameter
    private StatusService statusService;

    @SuppressWarnings("unused")
    @Parameter
    private UIService uiService;

    @SuppressWarnings("unused")
    @Parameter
    private PrefService prefService;

    @SuppressWarnings("unused")
    @Parameter
    private UnitService unitService;

    @SuppressWarnings("unused")
    @Parameter
    private LogService logService;

    private boolean calibrationWarned;

    @Override
    public void run() {

        statusService.showStatus("Ellipsoid Factor: initialising...");
        Img<BitType> inputAsBit = Common.toBitTypeImgPlus(opService,inputImage);
        final List<Vector3dc> internalSeedPoints = getRidgePoints(inputAsBit);
        //final List<Vector3d> internalSeedPoints = new ArrayList<>();
        //internalSeedPoints.add(new Vector3d(58.5,95.5, 128));
        //internalSeedPoints.add(new Vector3d(70.5,70.5,15.5));

        statusService.showStatus("Ellipsoid Factor: finding ellipsoids...");
        final List<Ellipsoid> ellipsoids = findEllipsoids(internalSeedPoints);
        //TODO fail loudly if ellipsoids.size()==0
        ellipsoids.sort(Comparator.comparingDouble(e -> -e.getVolume()));

        statusService.showStatus("Ellipsoid Factor: assigning EF to foreground voxels...");
        final Img<IntType> ellipsoidIdentityImage = assignEllipsoidID(inputAsBit, ellipsoids);
        writeOutputImages(ellipsoids, ellipsoidIdentityImage);

        long numberOfForegroundVoxels = countTrue(inputAsBit);
        long numberOfAssignedVoxels = countAssignedVoxels(ellipsoidIdentityImage);

        logService.initialize();
        logService.info("found "+ellipsoids.size()+" ellipsoids");
        logService.info("assigned voxels = "+numberOfAssignedVoxels);
        logService.info("foreground voxels = "+numberOfForegroundVoxels);
        double fillingPercentage = 100.0*((double) numberOfAssignedVoxels)/((double) numberOfForegroundVoxels);
        logService.info("filling percentage = "+fillingPercentage+"%");

    }

    private void writeOutputImages(List<Ellipsoid> ellipsoids, Img<IntType> ellipsoidIdentityImage) {
        final Img<FloatType> ellipsoidFactorImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        ellipsoidFactorImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));
        final Img<FloatType> volumeImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        volumeImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));
        final Img<FloatType> aToBImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        aToBImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));
        final Img<FloatType> bToCImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        bToCImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));

        final double[] ellipsoidFactorArray = ellipsoids.parallelStream().mapToDouble(e -> computeEllipsoidFactor(e)).toArray();
        mapValuesToImage(ellipsoidFactorArray, ellipsoidIdentityImage, ellipsoidFactorImage);

        final double[] volumeArray = ellipsoids.parallelStream().mapToDouble(e -> e.getVolume()).toArray();
        mapValuesToImage(volumeArray, ellipsoidIdentityImage, volumeImage);

        final double[] aToBArray = ellipsoids.parallelStream().mapToDouble(e -> e.getA()/e.getB()).toArray();
        mapValuesToImage(aToBArray, ellipsoidIdentityImage, aToBImage);

        final double[] bToCArray = ellipsoids.parallelStream().mapToDouble(e -> e.getB()/e.getC()).toArray();
        mapValuesToImage(bToCArray, ellipsoidIdentityImage, bToCImage);

        long FlinnPlotDimension = 501; //several ellipsoids may fall in same bin if this is too small a number! This will be ignored!
        final Img<BitType> flinnPlot = ArrayImgs.bits(FlinnPlotDimension,FlinnPlotDimension);
        flinnPlot.cursor().forEachRemaining(c -> c.setZero());

        final RandomAccess<BitType> flinnRA = flinnPlot.randomAccess();
        for(int i=0; i<aToBArray.length; i++)
        {
            long x = Math.round(aToBArray[i]*(FlinnPlotDimension-1));
            long y = Math.round(bToCArray[i]*(FlinnPlotDimension-1));
            flinnRA.setPosition(new long[]{x,FlinnPlotDimension-y-1});
            flinnRA.get().setOne();
        }

        Img<FloatType> flinnPeakPlot = ArrayImgs.floats(FlinnPlotDimension,FlinnPlotDimension);
        flinnPeakPlot.cursor().forEachRemaining(c -> c.set(0.0f));

        final RandomAccess<FloatType> flinnPeakPlotRA = flinnPeakPlot.randomAccess();
        final RandomAccess<IntType> idAccess = ellipsoidIdentityImage.randomAccess();
        final Cursor<IntType> idCursor = ellipsoidIdentityImage.localizingCursor();
        while(idCursor.hasNext())
        {
            idCursor.fwd();
            if(idCursor.get().getInteger()>=0)
            {
                long[] position = new long[3];
                idCursor.localize(position);
                idAccess.setPosition(position);
                int localMaxEllipsoidID = idAccess.get().getInteger();
                long x = Math.round(aToBArray[localMaxEllipsoidID]*(FlinnPlotDimension-1));
                long y = Math.round(bToCArray[localMaxEllipsoidID]*(FlinnPlotDimension-1));
                flinnPeakPlotRA.setPosition(new long[]{x,FlinnPlotDimension-y-1});
                final float currentValue = flinnPeakPlotRA.get().getRealFloat();
                flinnPeakPlotRA.get().set(currentValue+1.0f);
            }
        }

        if(sigma.getRealDouble()>0.0)
        {
            flinnPeakPlot = (Img<FloatType>) opService.filter().gauss(flinnPeakPlot, sigma.get());
        }

        efImage = new ImgPlus<>(ellipsoidFactorImage, "EF");
        efImage.setChannelMaximum(0,1);
        efImage.setChannelMinimum(  0,-1);
        efImage.initializeColorTables(1);
        efImage.setColorTable(ColorTables.FIRE, 0);

        eIdImage = new ImgPlus<>(ellipsoidIdentityImage, "ID");
        eIdImage.setChannelMaximum(0,ellipsoids.size()/10.0);
        eIdImage.setChannelMinimum(0, -1.0);

        vImage = new ImgPlus<>(volumeImage, "Volume");
        vImage.setChannelMaximum(0,ellipsoids.get(0).getVolume());
        vImage.setChannelMinimum(0, -1.0);

        aToBAxisRatioImage = new ImgPlus<>(aToBImage, "a/b");
        aToBAxisRatioImage.setChannelMaximum(0,1.0);
        aToBAxisRatioImage.setChannelMinimum(0, 0.0);

        bToCAxisRatioImage = new ImgPlus<>(bToCImage, "b/c");
        bToCAxisRatioImage.setChannelMaximum(0,1.0);
        bToCAxisRatioImage.setChannelMinimum(0, 0.0);

        flinnPlotImage = new ImgPlus<>(flinnPlot, "Unweighted Flinn Plot");
        flinnPlotImage.setChannelMaximum(0,255);
        flinnPlotImage.setChannelMinimum(0, 0);

        flinnPeakPlotImage = new ImgPlus<>(flinnPeakPlot, "Flinn Peak Plot");
        flinnPeakPlotImage.setChannelMaximum(0,255f);
        flinnPeakPlotImage.setChannelMinimum(0, 0.0f);
    }

    private long countAssignedVoxels(Img<IntType> ellipsoidIdentityImage) {
        long numberOfAssignedVoxels = 0;
        final Cursor<IntType> eIDCursor = ellipsoidIdentityImage.cursor();
        while(eIDCursor.hasNext())
        {
            eIDCursor.fwd();
            if(eIDCursor.get().getInteger()>-1)
            {
                numberOfAssignedVoxels++;
            }
        }
        return numberOfAssignedVoxels;
    }

    private Img<IntType> assignEllipsoidID(Img<BitType> inputAsBit, List<Ellipsoid> ellipsoids) {
        final Img<IntType> ellipsoidIdentityImage = ArrayImgs.ints(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        ellipsoidIdentityImage.cursor().forEachRemaining(c -> c.setInteger(-1));

        final LongStream zRange = LongStream.range(0, inputAsBit.dimension(2));

        zRange.parallel().forEach(sliceIndex -> {
            long[] mins = {0,0,sliceIndex};
            long[] maxs = {inputImage.dimension(0), inputImage.dimension(1), sliceIndex};

            //multiply by image unit? make more intelligent bounding box?
            final List<Ellipsoid> localEllipsoids = ellipsoids.stream().filter(e -> Math.abs(e.getCentroid().z() - sliceIndex) < e.getC()).collect(toList());

            final IntervalView<BitType> inputslice = Views.interval(inputAsBit, mins, maxs);
            final Cursor<BitType> inputCursor = inputslice.localizingCursor();
            while (inputCursor.hasNext()) {
                inputCursor.fwd();
                if(inputCursor.get().get()) {
                    long [] coordinates = new long[3];
                    inputCursor.localize(coordinates);
                    findID(localEllipsoids, Views.interval(ellipsoidIdentityImage, mins, maxs), new Vector3d(coordinates[0]+0.5, coordinates[1]+0.5, coordinates[2]+0.5));
                }
            }
        });
        return ellipsoidIdentityImage;
    }


    private List<Ellipsoid> findEllipsoids(List<Vector3dc> internalSeedPoints) {
        final List<Vector3d> filterSamplingDirections = getGeneralizedSpiralSetOnSphere(30);
        filterSamplingDirections.addAll(Arrays.asList(
                new Vector3d(1,0,0),new Vector3d(0,1,0),new Vector3d(0,0,1),
                new Vector3d(-1,0,0),new Vector3d(0,-1,0),new Vector3d(0,0,-1)));


        List<ValuePair<Set<ValuePair<Vector3d, Vector3d>>,Vector3d>> combinations = new ArrayList<>();
        return internalSeedPoints.parallelStream().map(sp -> getPointCombinationsForOneSeedPoint(sp)).flatMap(l -> l.stream())
                .map(c -> findLocalEllipsoidOp.calculate(new ArrayList<>(c.getA()), c.getB()))
                .filter(Optional::isPresent).map(Optional::get)
                .filter(e -> whollyContainedInForeground(e, filterSamplingDirections)).collect(Collectors.toList());
    }

    private List<ValuePair<Set<ValuePair<Vector3dc, Vector3dc>>, Vector3dc>> getPointCombinationsForOneSeedPoint(Vector3dc internalSeedPoint)
    {
        int nSphere = 30;
        final List<Vector3d> sphereSamplingDirections = getGeneralizedSpiralSetOnSphere(nSphere);
        //sphereSamplingDirections.clear();
        sphereSamplingDirections.addAll(Arrays.asList(
                new Vector3d(1,0,0),new Vector3d(0,1,0),new Vector3d(0,0,1),
                new Vector3d(-1,0,0),new Vector3d(0,-1,0),new Vector3d(0,0,-1)));
        List<ValuePair<Set<ValuePair<Vector3dc, Vector3dc>>,Vector3dc>> combinations = new ArrayList<>();

        List<Vector3dc> contactPoints = sphereSamplingDirections.stream().map(d -> {
            final Vector3d direction = new Vector3d(d);
            return findFirstPointInBGAlongRay(direction, internalSeedPoint);
        }).collect(toList());
        final List<ValuePair<Vector3dc, Vector3dc>> seedPoints = new ArrayList<>();

        contactPoints.forEach(c -> {
            Vector3d inwardDirection = new Vector3d(internalSeedPoint);
            inwardDirection.sub(c);
            inwardDirection.normalize();
            seedPoints.add(new ValuePair<>(c, inwardDirection));
        });
        combinations.addAll(getAllUniqueCombinationsOfFourPoints(seedPoints, internalSeedPoint));
        return combinations;
    }

    private List<Vector3dc> getRidgePoints(Img<BitType> inputAsBit) {
        final RandomAccess<BitType> inputBitRA = inputAsBit.randomAccess();

        //find clever seed points
        final Img<R> distanceTransform = (Img<R>) opService.image().distancetransform(inputAsBit);

        List<Shape> shapes = new ArrayList<>();
        shapes.add(new HyperSphereShape(2));
        final IterableInterval<R> open = opService.morphology().open(distanceTransform, shapes);
        final IterableInterval<R> close = opService.morphology().close(distanceTransform, shapes);
        final IterableInterval<R> ridge = opService.math().subtract(close, open);
        final Cursor<R> ridgeCursor = ridge.localizingCursor();

        //remove ridgepoints in BG - how does this make a difference?
        while(ridgeCursor.hasNext()){
            ridgeCursor.fwd();
            long[] position = new long[3];
            ridgeCursor.localize(position);
            inputBitRA.setPosition(position);
            if(!inputBitRA.get().get())
            {
                ridgeCursor.get().setReal(0.0f);
            }
        }

        final double ridgePointCutOff = percentageOfRidgePoints.getRealFloat()*opService.stats().max(ridge).getRealFloat();
        final Img<R> ridgeImg = (Img) ridge;
        final Img<BitType> thresholdedRidge = Thresholder.threshold(ridgeImg, (R) new FloatType((float) ridgePointCutOff), true, 1);
        ridgePointsImage = new ImgPlus<>(opService.convert().uint8(thresholdedRidge), "Seeding Points");

        final List<Vector3dc> internalSeedPoints = new ArrayList<>();

        ridgeCursor.reset();
        while (ridgeCursor.hasNext()) {
            ridgeCursor.fwd();
            final double localValue = ridgeCursor.get().getRealFloat();
            if (localValue > ridgePointCutOff) {
                long[] position = new long[3];
                ridgeCursor.localize(position);
                final Vector3dc internalSeedPoint = new Vector3d(position[0]+0.5, position[1]+0.5, position[2]+0.5);
                internalSeedPoints.add(internalSeedPoint);
            }
        }
        return internalSeedPoints;
    }

    private void mapValuesToImage(double[] values, Img<IntType> ellipsoidIdentityImage, Img<FloatType> ellipsoidFactorImage) {
        final RandomAccess<FloatType> ef = ellipsoidFactorImage.randomAccess();
        final Cursor<IntType> id = ellipsoidIdentityImage.localizingCursor();
        while(id.hasNext()){
            id.fwd();
            if(id.get().getInteger()!=-1){
                long[] position = new long[3];
                id.localize(position);
                final double value = values[id.get().getInteger()];
                ef.setPosition(position);
                ef.get().setReal(value);
            }
        }
    }

    private void findID(final List<Ellipsoid> ellipsoids, final RandomAccessibleInterval<IntType> ellipsoidIdentityImage, final Vector3d point) {

        //find largest ellipsoid containing current position
        int currentEllipsoidCounter = 0;
        while (currentEllipsoidCounter < ellipsoids.size() && !insideEllipsoid(point, ellipsoids.get(currentEllipsoidCounter))) {
            currentEllipsoidCounter++;
        }

        //ignore background voxels and voxels not contained in any ellipsoid
        if (currentEllipsoidCounter < ellipsoids.size()) {
            final RandomAccess<IntType> eIDRandomAccess = ellipsoidIdentityImage.randomAccess();
            eIDRandomAccess.setPosition(vectorToPixelGrid(point));
            eIDRandomAccess.get().set(currentEllipsoidCounter);
        }
    }

    private boolean whollyContainedInForeground(final Ellipsoid e, final List<Vector3d> sphereSamplingDirections) {
        if(!isInBounds(vectorToPixelGrid(e.getCentroid())))
        {
            return false;
        }

        List<Vector3d> axisSamplingDirections = new ArrayList<>();
        Matrix3d ellipsoidOrientation = new Matrix3d();
        e.getOrientation().get3x3(ellipsoidOrientation);
        axisSamplingDirections.add(new Vector3d(ellipsoidOrientation.m00(),ellipsoidOrientation.m01(),ellipsoidOrientation.m02()));
        axisSamplingDirections.add(new Vector3d(-ellipsoidOrientation.m00(),-ellipsoidOrientation.m01(),-ellipsoidOrientation.m02()));

        axisSamplingDirections.add(new Vector3d(ellipsoidOrientation.m10(),ellipsoidOrientation.m11(),ellipsoidOrientation.m12()));
        axisSamplingDirections.add(new Vector3d(-ellipsoidOrientation.m10(),-ellipsoidOrientation.m11(),-ellipsoidOrientation.m12()));

        axisSamplingDirections.add(new Vector3d(ellipsoidOrientation.m20(),ellipsoidOrientation.m21(),ellipsoidOrientation.m22()));
        axisSamplingDirections.add(new Vector3d(-ellipsoidOrientation.m20(),-ellipsoidOrientation.m21(),-ellipsoidOrientation.m22()));

        axisSamplingDirections.addAll(sphereSamplingDirections);

        return !axisSamplingDirections.stream().anyMatch(dir -> ellipsoidIntersectionIsBackground(e,dir));
    }

    private boolean ellipsoidIntersectionIsBackground(Ellipsoid e, Vector3d dir) {
        double axisReduction = Math.sqrt(3);
        double a = e.getA()-axisReduction;
        double b = e.getB()-axisReduction;
        double c = e.getC()-axisReduction;
        
        final Matrix3d Q = new Matrix3d();
        e.getOrientation().get3x3(Q);
        
        final Matrix3d lambda = new Matrix3d();
        lambda.scaling(1.0/(a*a),1.0/(b*b),1.0/(c*c));
        
        Matrix3d LambdaQ = new Matrix3d(lambda);
        LambdaQ.mul(Q);
        Matrix3d QT = Q.transpose();
        Matrix3d QTLambdaQ = new Matrix3d(QT);
        QTLambdaQ.mul(LambdaQ);
        
        Vector3d ATimesDir = new Vector3d();
        QTLambdaQ.transform(dir,ATimesDir);
        double surfaceIntersectionParameter = Math.sqrt(1.0/dir.dot(ATimesDir));
        
        Vector3d intersectionPoint = new Vector3d(dir);
        intersectionPoint.mul(surfaceIntersectionParameter);
        intersectionPoint.add(e.getCentroid());
        
        final long[] pixel = vectorToPixelGrid(intersectionPoint);
        if (isInBounds(pixel)) {
            final RandomAccess<R> inputRA = inputImage.getImg().randomAccess();
            inputRA.setPosition(pixel);
            return inputRA.get().getRealDouble() == 0;
        }
        else {
            return true;//false to have outside input image equals foreground
        }
    }

    private static float computeEllipsoidFactor(final Ellipsoid ellipsoid) {
        return (float) (ellipsoid.getA() / ellipsoid.getB() - ellipsoid.getB() / ellipsoid.getC());
    }

    static boolean insideEllipsoid(final Vector3d coordinates, final Ellipsoid ellipsoid) {
        final Vector3d centroid = ellipsoid.getCentroid();
        double c = ellipsoid.getC();

        //TODO restate in one statement with && and ||
        if(Math.abs(coordinates.x()-centroid.x())>c) return false;
        if(Math.abs(coordinates.y()-centroid.y())>c) return false;
        if(Math.abs(coordinates.z()-centroid.z())>c) return false;

        Vector3d x = new Vector3d(coordinates);
        x.sub(centroid);

        if(x.length()>ellipsoid.getC()) return false;

        Matrix3d orientation = new Matrix3d();
        ellipsoid.getOrientation().get3x3(orientation);

        Matrix3d eigenMatrix = new Matrix3d();
        eigenMatrix.scaling(1.0 / (ellipsoid.getA() * ellipsoid.getA()),1.0 / (ellipsoid.getB() * ellipsoid.getB()),1.0 / (ellipsoid.getC() * ellipsoid.getC()));

        eigenMatrix.mul(orientation, eigenMatrix);
        final Matrix3d orientationTransposed = orientation.transpose();
        orientationTransposed.mul(eigenMatrix, eigenMatrix);

        Vector3d Ax = new Vector3d();
        eigenMatrix.transform(x, Ax);

        return x.dot(Ax) < 1;
    }

    /**
     * Method to numerically approximate equidistantly spaced points on the
     * surface of a sphere
     * <p>
     * The implementation follows the description of the theoretical work by
     * Rakhmanov et al., 1994 in Saff and Kuijlaars, 1997
     * (<a href="doi:10.1007/BF03024331">dx.doi.org/10.1007/BF03024331</a>), but k
     * is shifted by one to the left for more convenient indexing.
     *
     * @param n : number of points required (has to be > 2)
     * </p>
     */
    static List<Vector3d> getGeneralizedSpiralSetOnSphere(int n) {
        List<Vector3d> spiralSet = new ArrayList<>();

        List<Double> phi = new ArrayList<>();
        phi.add(0.0);
        for (int k = 1; k < n - 1; k++) {
            double h = -1.0 + 2.0 * ((double) k) / (n - 1);
            phi.add(getPhiByRecursion(n, phi.get(k - 1), h));
        }
        phi.add(0.0);

        for (int k = 0; k < n; k++) {
            double h = -1.0 + 2.0 * ((double) k) / (n - 1);
            double theta = Math.acos(h);
            spiralSet.add(new Vector3d(Math.sin(theta) * Math.cos(phi.get(k)), Math
                    .sin(theta) * Math.sin(phi.get(k)), Math.cos(theta)));

        }

        return spiralSet;
    }

    private static double getPhiByRecursion(double n, double phiKMinus1,
                                            double hk) {
        double phiK = phiKMinus1 + 3.6 / Math.sqrt(n) * 1.0 / Math.sqrt(1 - hk *
                hk);
        // modulo 2pi calculation works for positive numbers only, which is not a
        // problem in this case.
        return phiK - Math.floor(phiK / (2 * Math.PI)) * 2 * Math.PI;
    }

    private static int estimateNSpiralPointsRequired(double searchRadius,
                                                     double pixelWidth) {
        return (int) Math.ceil(Math.pow(searchRadius * 3.809 / pixelWidth, 2));
    }

    Vector3d findFirstPointInBGAlongRay(final Vector3dc rayIncrement,
                                        final Vector3dc start) {
        RandomAccess<R> randomAccess = inputImage.randomAccess();

        Vector3d currentRealPosition = new Vector3d(start);
        long[] currentPixelPosition = vectorToPixelGrid(start);
        randomAccess.setPosition(currentPixelPosition);

        while (randomAccess.get().getRealDouble() > 0) {
            currentRealPosition.add(rayIncrement);
            currentPixelPosition = vectorToPixelGrid(currentRealPosition);
            if (!isInBounds(currentPixelPosition)) break;
            randomAccess.setPosition(currentPixelPosition);
        }
        return currentRealPosition;
    }


    private boolean isInBounds(long[] currentPixelPosition) {
        long width = inputImage.dimension(0);
        long height = inputImage.dimension(1);
        long depth = inputImage.dimension(2);
        return !(currentPixelPosition[0] < 0 || currentPixelPosition[0] >= width ||
                currentPixelPosition[1] < 0 || currentPixelPosition[1] >= height ||
                currentPixelPosition[2] < 0 || currentPixelPosition[2] >= depth);
    }

    private static long[] vectorToPixelGrid(Vector3dc currentPosition) {
        return Stream.of(currentPosition.x(), currentPosition.y(),
                currentPosition.z()).mapToLong(x -> (long) x.doubleValue()).toArray();
    }

    static List<ValuePair<Set<ValuePair<Vector3dc,Vector3dc>>,Vector3dc>> getAllUniqueCombinationsOfFourPoints(final List<ValuePair<Vector3dc,Vector3dc>> points, final Vector3dc centre){
        final Iterator<int[]> iterator = CombinatoricsUtils.combinationsIterator(points.size(), 4);
        final List<ValuePair<Set<ValuePair<Vector3dc,Vector3dc>>,Vector3dc>> pointCombinations = new ArrayList<>();
        iterator.forEachRemaining(el ->
                {
                    final Set<ValuePair<Vector3dc, Vector3dc>> pointCombination =  IntStream.range(0, 4).mapToObj(i -> points.get(el[i])).collect(Collectors.toSet());
                    if(pointCombination.size()==4)
                    {
                        pointCombinations.add(new ValuePair<>(pointCombination,centre));
                    }
                }
        );
        return pointCombinations;
    }

    @SuppressWarnings("unused")
    private void validateImage() {
        if (inputImage == null) {
            cancel(NO_IMAGE_OPEN);
            return;
        }
        if (AxisUtils.countSpatialDimensions(inputImage) != 3) {
            cancel(NOT_3D_IMAGE);
            return;
        }
        if (!ElementUtil.isColorsBinary(inputImage)) {
            cancel(NOT_BINARY);
            return;
        }
        if (!isCalibrationIsotropic() && !calibrationWarned) {
            final DialogPrompt.Result result = uiService.showDialog(
                    "The voxels in the image are anisotropic, which may affect results. Continue anyway?",
                    WARNING_MESSAGE, OK_CANCEL_OPTION);
            // Avoid showing warning more than once (validator gets called before and
            // after dialog pops up..?)
            calibrationWarned = true;
            if (result != OK_OPTION) {
                cancel(null);
            }
        }
    }

    // TODO Refactor into a static utility method with unit tests
    private boolean isCalibrationIsotropic() {
        final Optional<String> commonUnit = getSpatialUnit(inputImage, unitService);
        if (!commonUnit.isPresent()) {
            return false;
        }
        final String unit = commonUnit.get();
        return spatialAxisStream(inputImage).map(axis -> unitService.value(axis
                .averageScale(0, 1), axis.unit(), unit)).distinct().count() == 1;
    }
    // endregion
}
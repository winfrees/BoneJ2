package org.bonej.ops.connectivity;

import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccess;
import net.imglib2.type.logic.BitType;
import org.bonej.testImages.Cuboid;
import org.bonej.testImages.IJ1ImgPlus;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the {@link EulerCorrection EulerContribution} Op
 *
 * TODO: Write more tests
 * @author Richard Domander 
 */
public class EulerCorrectionTest {
    private static final ImageJ IMAGE_J = new ImageJ();

    @AfterClass
    public static void oneTimeTearDown() {
        IMAGE_J.context().dispose();
    }

    /** Regression test EulerCharacteristic with a solid cuboid that never touches the edges of the stack */
    @Test
    public void testCompute1CuboidFreeFloat() throws Exception {
        final ImgPlus<BitType> cuboid =
                (ImgPlus<BitType>) IMAGE_J.op().run(Cuboid.class, null, 10, 10, 10, 1, 1, 5);
        final EulerCorrection.Traverser<BitType> traverser = new EulerCorrection.Traverser<>(cuboid);

        final int vertices = EulerCorrection.stackCorners(traverser);
        assertEquals("Number of stack vertices is incorrect", 0, vertices);

        final long edges = EulerCorrection.stackEdges(traverser);
        assertEquals("Number stack edge voxels is incorrect", 0, edges);

        final int faces = EulerCorrection.stackFaces(traverser);
        assertEquals("Number stack face voxels is incorrect", 0, faces);

        final long voxelEdgeIntersections = EulerCorrection.voxelEdgeIntersections(traverser);
        assertEquals("Number intersections is incorrect", 0, voxelEdgeIntersections);

        final long voxelFaceIntersections = EulerCorrection.voxelFaceIntersections(traverser);
        assertEquals("Number intersections is incorrect", 0, voxelFaceIntersections);

        final long voxelEdgeFaceIntersections = EulerCorrection.voxelEdgeFaceIntersections(traverser);
        assertEquals("Number intersections is incorrect", 0, voxelEdgeFaceIntersections);

        final Double result = (Double) IMAGE_J.op().run(EulerCorrection.class, cuboid);
        assertEquals("Euler contribution is incorrect", 0, result.intValue());
    }

    /**
     * Regression test EulerCharacteristic with a solid cuboid that's the same size as the image,
     * i.e. all faces touch the edges
     */
    @Test
    public void testCompute1CuboidStackSize() throws Exception {
        final int edges = 12;
        final int cubeSize = 3;
        final int edgeSize = cubeSize - 2;
        final ImgPlus<BitType> cuboid =
                (ImgPlus<BitType>) IMAGE_J.op().run(Cuboid.class, null, cubeSize, cubeSize, cubeSize, 1, 1, 0);
        final EulerCorrection.Traverser<BitType> traverser = new EulerCorrection.Traverser<>(cuboid);

        final int vertices = EulerCorrection.stackCorners(traverser);
        assertEquals("Number of stack vertices is incorrect", 8, vertices);

        final long stackEdges = EulerCorrection.stackEdges(traverser);
        assertEquals("Number stack edge voxels is incorrect", edges * edgeSize, stackEdges);

        final int faces = EulerCorrection.stackFaces(traverser);
        assertEquals("Number stack face voxels is incorrect", 6 * edgeSize * edgeSize, faces);

        final long voxelEdgeIntersections = EulerCorrection.voxelEdgeIntersections(traverser);
        // you can fit n - 1 2x1 edges on edges whose size is n
        final long expectedVEIntersections = edges * (cubeSize - 1);
        assertEquals("Number intersections is incorrect", expectedVEIntersections, voxelEdgeIntersections);

        final long xyVFIntersections = (cubeSize + 1) * (cubeSize + 1);
        final long yzVFIntersections = (cubeSize - 1) * (cubeSize + 1);
        final long xzVFIntersections = (cubeSize - 1) * (cubeSize - 1);
        final long expectedVFIntersections = xyVFIntersections * 2 + yzVFIntersections * 2 + xzVFIntersections * 2;
        final long voxelFaceIntersections = EulerCorrection.voxelFaceIntersections(traverser);
        assertEquals("Number intersections is incorrect", expectedVFIntersections, voxelFaceIntersections);

        final long voxelEdgeFaceIntersections = EulerCorrection.voxelEdgeFaceIntersections(traverser);
        assertEquals("Number intersections is incorrect", 108, voxelEdgeFaceIntersections);

        final Double result = (Double) IMAGE_J.op().run(EulerCorrection.class, cuboid);
        assertEquals("Euler contribution is incorrect", 1, result.intValue());
    }

    @Test
    public void testHyperStack() throws Exception {
        // Create a hyperstack with two channels and frames
        final ImgPlus<BitType> imgPlus = IJ1ImgPlus.createIJ1ImgPlus(IMAGE_J.op(), "", 5, 5, 5, 2, 2);
        final RandomAccess<BitType> access = imgPlus.randomAccess();
        // Add a particle to the middle of a face in channel 1, frame 0
        access.setPosition(new long[]{3, 3, 1, 0, 0});
        access.get().setOne();
        // Add a particle to the middle of a face in channel 0, frame 1
        access.setPosition(new long[]{3, 3, 0, 0, 1});
        access.get().setOne();


        // Tests channel 0, frame 0
        Double result = (Double) IMAGE_J.op().run(EulerCorrection.class, imgPlus, Arrays.asList(0L, 0L, 0L, 0L, 0L));
        assertEquals("Euler correction is incorrect", 0.0, result, 1e-12);

        // Tests channel 1, frame 0
        result = (Double) IMAGE_J.op().run(EulerCorrection.class, imgPlus, Arrays.asList(0L, 0L, 1L, 0L, 0L));
        assertEquals("Euler correction is incorrect", 0.5, result, 1e-12);

        // Tests channel 0, frame 1
        result = (Double) IMAGE_J.op().run(EulerCorrection.class, imgPlus, Arrays.asList(0L, 0L, 0L, 0L, 1L));
        assertEquals("Euler correction is incorrect", 0.5, result, 1e-12);

        // Tests channel 1, frame 1
        result = (Double) IMAGE_J.op().run(EulerCorrection.class, imgPlus, Arrays.asList(0L, 0L, 1L, 0L, 1L));
        assertEquals("Euler correction is incorrect", 0.0, result, 1e-12);
    }
}
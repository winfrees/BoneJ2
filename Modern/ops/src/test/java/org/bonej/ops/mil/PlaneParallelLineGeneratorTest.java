/*
BSD 2-Clause License
Copyright (c) 2018, Michael Doube, Richard Domander, Alessandro Felder
All rights reserved.
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.bonej.ops.mil;

import static org.bonej.ops.mil.ParallelLineGenerator.Line;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.stream.Stream;

import net.imagej.ImageJ;
import net.imagej.ops.linalg.rotate.Rotate3d;
import net.imagej.ops.special.hybrid.BinaryHybridCFI1;
import net.imagej.ops.special.hybrid.Hybrids;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;

import net.imglib2.img.basictypeaccess.array.LongArray;
import net.imglib2.type.logic.BitType;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Tests for {@link PlaneParallelLineGenerator}.
 *
 * @author Richard Domander
 */
public class PlaneParallelLineGeneratorTest {

	private static ImageJ IMAGE_J = new ImageJ();
	private static final Quaterniondc IDENTITY = new Quaterniond(new AxisAngle4d(
		0, 0, 1, 0));
	private static final int SIZE = 5;
	private static Img<BitType> IMG = ArrayImgs.bits(SIZE, SIZE, SIZE);
	private static BinaryHybridCFI1<Vector3d, Quaterniondc, Vector3d> rotateOp;
	@Rule
	public ExpectedException thrown = ExpectedException.none();

	@Test(expected = NullPointerException.class)
	public void testConstructorThrowsNPEIfIntervalNull() {
		new PlaneParallelLineGenerator(null, IDENTITY, rotateOp, 1);
	}

	@Test(expected = NullPointerException.class)
	public void testConstructorThrowsNPEIfRotationNull() {
		new PlaneParallelLineGenerator(IMG, null, rotateOp, 1);
	}

	@Test(expected = NullPointerException.class)
	public void testConstructorThrowsNPEIfOpNull() {
		new PlaneParallelLineGenerator(IMG, IDENTITY, null, 1);
	}

	@Test
	public void testConstructorThrowsIAEIfSectionsNotPositive() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("Sections must be positive");

		new PlaneParallelLineGenerator(IMG, IDENTITY, rotateOp, 0);
	}

	@Test
	public void testConstructorThrowsIAEIfIntervalNot3D() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("Interval must be 3D");
		final ArrayImg<BitType, LongArray> twoDImg = ArrayImgs.bits(SIZE, SIZE);

		new PlaneParallelLineGenerator(twoDImg, IDENTITY, rotateOp, 1);
	}

	@Test
	public void testGetDirection() {
		final Vector3d expectedDirection = new Vector3d(0, 0, 1);
		final PlaneParallelLineGenerator
				plane = new PlaneParallelLineGenerator(IMG, IDENTITY, rotateOp, 1);

		final Vector3dc direction = plane.getDirection();

		assertEquals("Incorrect direction", expectedDirection, direction);
	}

	@Test
	public void testGetDirectionRotation() {
		// SETUP
		final Quaterniondc rotation = new Quaterniond(new AxisAngle4d(Math.PI / 2.0,
			0, 1, 0));
		final Vector3d expectedDirection = IMAGE_J.op().linalg().rotate(
			new Vector3d(0, 0, 1), rotation);
		final PlaneParallelLineGenerator
				plane = new PlaneParallelLineGenerator(IMG, rotation, rotateOp, 1);

		// EXECUTE
		final Vector3dc direction = plane.getDirection();

		// VERIFY
		assertEquals("Direction rotated incorrectly", expectedDirection, direction);
	}

	@Test
	public void testAllQuadrantsCovered() {
		final PlaneParallelLineGenerator generator =
				new PlaneParallelLineGenerator(IMG, IDENTITY, rotateOp, 2L);
		final double planeSize = Math.sqrt(SIZE * SIZE * 3);
		final double centre = (planeSize - planeSize * 0.5) / 2.0;

		final long quadrants = Stream.generate(generator::nextLine).limit(4).map(l -> l.point)
				.map(p -> identifyQuadrant(p, centre)).distinct().count();

		assertEquals("There's a line missing from one or more quadrants of the plane",
			4, quadrants);
	}

	@Test
	public void testFirstQuadrantChanges() {
		PlaneParallelLineGenerator generator =
				new PlaneParallelLineGenerator(IMG, IDENTITY, rotateOp, 2L);
		final double planeSize = Math.sqrt(SIZE * SIZE * 3);
		final double centre = (planeSize - planeSize * 0.5) / 2.0;
		final int initialQuadrant = identifyQuadrant(generator.nextLine().point, centre);
		final int max = 100_000;
		int generated = 0;
		boolean changed = false;

		while (generated < max) {
			generator = new PlaneParallelLineGenerator(IMG, IDENTITY, rotateOp, 2L);
			final int quadrant = identifyQuadrant(generator.nextLine().point, centre);
			if (quadrant != initialQuadrant) {
				changed = true;
			}
			generated++;
		}

		assertTrue("All the lines came from the same quadrant. " +
				"Either you're unlucky, or generation isn't truly random", changed);
	}

	@Test
	public void testGetOriginsCoPlanar() {
		// SETUP
		final double translation = -(Math.sqrt(SIZE * SIZE * 3.0) / 2.0) + SIZE /
			2.0;
		final Vector3dc pointOnPlane = new Vector3d(translation, translation, SIZE /
			2.0);
		final Vector3dc normal = new Vector3d(0, 0, 1);
		final PlaneParallelLineGenerator generator =
				new PlaneParallelLineGenerator(IMG, IDENTITY, rotateOp, 2L);

		// EXECUTE
		final Stream<Line> origins = Stream.generate(generator::nextLine).limit(4);

		// VERIFY
		origins.forEach(l -> {
			final Vector3d a = new Vector3d(l.point);
			a.sub(pointOnPlane);
			assertEquals("Point " + a + " is not on the expected plane", 0.0, normal
				.dot(a), 0.0);
		});
	}

	@Test
	public void testGetOriginsRotation() {
		// SETUP
		final double translation = -(Math.sqrt(SIZE * SIZE * 3) / 2.0) + SIZE / 2.0;
		final Vector3dc pointOnPlane = new Vector3d(SIZE / 2.0, translation,
			translation);
		final Vector3dc normal = new Vector3d(1, 0, 0);
		final Quaterniondc rotation = new Quaterniond(new AxisAngle4d(Math.PI / 2.0,
			0, 1, 0));
		final PlaneParallelLineGenerator generator =
				new PlaneParallelLineGenerator(IMG, rotation, rotateOp, 2L);

		// EXECUTE
		final Stream<Line> origins = Stream.generate(generator::nextLine).limit(4);

		// VERIFY
		origins.forEach(l -> {
			final Vector3d a = new Vector3d(l.point);
			a.sub(pointOnPlane);
			assertEquals("Point " + a + " rotated incorrectly", 0.0, normal.dot(a),
				1e-12);
		});
	}

	@BeforeClass
	public static void oneTimeSetup() {
		rotateOp = Hybrids.binaryCFI1(IMAGE_J.op(), Rotate3d.class, Vector3d.class,
			new Vector3d(), new Quaterniond());
	}

	@AfterClass
	public static void oneTimeTearDown() {
		IMAGE_J.context().dispose();
		IMAGE_J = null;
		IMG = null;
	}

	private static int identifyQuadrant(final Vector3dc v, final double centre) {
		if (v.x() <= centre) {
			if (v.y() <= centre) {
				return 1;
			} else {
				return 2;
			}
		}
		if (v.y() <= centre) {
			return 3;
		}
		return 4;
	}
}

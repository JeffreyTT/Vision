/*
 * Copyright (c) 2019 Titan Robotics Club (http://www.titanrobotics.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package raspivision;

import com.google.gson.Gson;
import edu.wpi.cscore.CvSink;
import edu.wpi.cscore.CvSource;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.EntryListenerFlags;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.Arrays;
import java.util.Comparator;

public class RaspiVision
{
    private enum DebugDisplayType
    {
        NONE, REGULAR, BOUNDING_BOX, MASK, CORNERS, FULL_PNP
    }

    private static final int TEAM_NUMBER = 492;
    private static final boolean SERVER = true; // true for debugging only
    private static final boolean MEASURE_FPS = true;
    private static final double FPS_AVG_WINDOW = 5; // 5 seconds
    private static final DebugDisplayType DEBUG_DISPLAY = DebugDisplayType.NONE;

    // Default image resolution, in pixels
    private static final int DEFAULT_WIDTH = 320;
    private static final int DEFAULT_HEIGHT = 240;

    // From the raspberry pi camera spec sheet, in degrees:
    private static final double CAMERA_FOV_X = 62.2;
    private static final double CAMERA_FOV_Y = 48.8;

    // These were calculated using the game manual specs on vision target
    // Origin is center of bounding box
    // Order is leftleftcorner, leftrightcorner, leftbottomcorner, lefttopcorner, rightleftcorner, rightrightcorner, rightbottomcorner, righttopcorner
    private static final Point3[] TARGET_WORLD_COORDS = new Point3[] { new Point3(-7.3125, -2.4375, 0),
        new Point3(-4.0, 2.4375, 0), new Point3(-5.375, -2.9375, 0), new Point3(-5.9375, 2.9375, 0),
        new Point3(4.0, 2.4375, 0), new Point3(7.3125, -2.4375, 0), new Point3(5.375, -2.9375, 0),
        new Point3(5.9375, 2.9375, 0) };

    public static void main(String[] args)
    {
        // Load the C++ native code
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        RaspiVision vision = new RaspiVision();
        vision.start();
    }

    private Gson gson;
    private Thread visionThread;
    private Thread calcThread;
    private Thread cameraThread;

    private NetworkTableEntry visionData;
    private NetworkTableEntry cameraData;

    private int numFrames = 0;
    private double startTime = 0;
    private CvSource dashboardDisplay;

    private int width, height; // in pixels
    private double focalLength; // In pixels

    private final Object dataLock = new Object();
    private TargetData targetData = null;

    private final Object imageLock = new Object();
    private VisionTargetPipeline pipeline;
    private UsbCamera camera;
    private Mat image = null;

    // Instantiating Mats are expensive, so do it all up here, and just use the put methods.
    private Mat dummyCameraMatrix = new Mat();
    private Mat dummyRotMatrix = new Mat();
    private Mat dummyTransVect = new Mat();
    private Mat dummyRotMatX = new Mat();
    private Mat dummyRotMatY = new Mat();
    private Mat dummyRotMatZ = new Mat();
    private MatOfDouble dist = new MatOfDouble(0, 0, 0, 0);
    private MatOfPoint2f imagePoints = new MatOfPoint2f();
    private MatOfPoint3f worldPoints = new MatOfPoint3f(TARGET_WORLD_COORDS);
    private volatile Mat cameraMat = null; // This is being updated by one thread and consumed by another, so volatile.
    private Mat rotationVector = new Mat();
    private Mat translationVector = new Mat();
    private Mat rotationMatrix = new Mat();
    private Mat projectionMatrix = new Mat();
    private MatOfPoint2f projectedPoints = new MatOfPoint2f();
    private MatOfPoint3f pointToProject = new MatOfPoint3f();
    private MatOfPoint contourPoints = new MatOfPoint();
    private Mat eulerAngles = new Mat();

    public RaspiVision()
    {
        gson = new Gson();

        NetworkTableInstance instance = NetworkTableInstance.getDefault();
        if (SERVER)
        {
            System.out.print("Initializing server...");
            instance.startServer();
            System.out.println("Done!");
        }
        else
        {
            System.out.print("Connecting to server...");
            instance.startClientTeam(TEAM_NUMBER);
            System.out.println("Done!");
        }

        System.out.print("Initializing vision...");

        NetworkTable table = instance.getTable("RaspiVision");
        NetworkTableEntry cameraConfig = table.getEntry("CameraConfig");
        visionData = table.getEntry("VisionData");
        cameraData = table.getEntry("CameraData");
        NetworkTableEntry hueLow = table.getEntry("HueLow");
        NetworkTableEntry hueHigh = table.getEntry("HueHigh");
        NetworkTableEntry satLow = table.getEntry("SatLow");
        NetworkTableEntry satHigh = table.getEntry("SatHigh");
        NetworkTableEntry luminanceLow = table.getEntry("LuminanceLow");
        NetworkTableEntry luminanceHigh = table.getEntry("LuminanceHigh");

        cameraData.setDoubleArray(new double[] { DEFAULT_WIDTH, DEFAULT_HEIGHT });

        if (DEBUG_DISPLAY != DebugDisplayType.NONE)
        {
            dashboardDisplay = CameraServer.getInstance().putVideo("RaspiVision", DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }

        cameraThread = new Thread(this::cameraCaptureThread);
        calcThread = new Thread(this::calculationThread);

        camera = CameraServer.getInstance().startAutomaticCapture();
        camera.setResolution(DEFAULT_WIDTH, DEFAULT_HEIGHT); // Default to 320x240, unless overridden by json config
        camera.setBrightness(40);
        pipeline = new VisionTargetPipeline();
        visionThread = new Thread(this::visionProcessingThread);
        visionThread.setDaemon(false);

        int flag = EntryListenerFlags.kNew | EntryListenerFlags.kUpdate;

        hueHigh.setDouble(pipeline.hslThresholdHue[1]);
        hueHigh.addListener(event -> pipeline.hslThresholdHue[1] = event.value.getDouble(), flag);
        hueLow.setDouble(pipeline.hslThresholdHue[0]);
        hueLow.addListener(event -> pipeline.hslThresholdHue[0] = event.value.getDouble(), flag);

        satHigh.setDouble(pipeline.hslThresholdSaturation[1]);
        satHigh.addListener(event -> pipeline.hslThresholdSaturation[1] = event.value.getDouble(), flag);
        satLow.setDouble(pipeline.hslThresholdSaturation[0]);
        satLow.addListener(event -> pipeline.hslThresholdSaturation[0] = event.value.getDouble(), flag);

        luminanceHigh.setDouble(pipeline.hslThresholdLuminance[1]);
        luminanceHigh.addListener(event -> pipeline.hslThresholdLuminance[1] = event.value.getDouble(), flag);
        luminanceLow.setDouble(pipeline.hslThresholdLuminance[0]);
        luminanceLow.addListener(event -> pipeline.hslThresholdLuminance[0] = event.value.getDouble(), flag);

        cameraConfig.addListener(event -> configCamera(camera, event.value.getString()),
            EntryListenerFlags.kNew | EntryListenerFlags.kUpdate | EntryListenerFlags.kImmediate);

        System.out.println("Done!\nInitialization complete!");
    }

    private void configCamera(UsbCamera camera, String json)
    {
        System.out.print("Configuring camera...");
        if (!camera.setConfigJson(json))
        {
            System.out.println();
            System.err.println("Invalid json configuration file!");
        }
        else
        {
            System.out.println("Done!");
        }
    }

    public void start()
    {
        System.out.print("Starting vision thread...");
        cameraThread.start();
        visionThread.start();
        calcThread.start();
        startTime = getTime();
        System.out.println("Done!");
    }

    private void cameraCaptureThread()
    {
        CvSink sink = new CvSink("RaspiVision");
        sink.setSource(camera);
        Mat image = new Mat();
        while (!Thread.interrupted())
        {
            long response = sink.grabFrame(image);
            if (response != 0L)
            {
                Mat frame = image.clone();
                synchronized (imageLock)
                {
                    this.image = frame;
                    imageLock.notify();
                }
            }
            else
            {
                System.err.println(sink.getError());
            }
        }
    }

    private void visionProcessingThread()
    {
        try
        {
            Mat image = null;
            while (!Thread.interrupted())
            {
                synchronized (imageLock)
                {
                    while (this.image == image)
                    {
                        imageLock.wait();
                    }
                    image = this.image;
                }
                pipeline.process(image);
                processImage(pipeline);
                // I don't need the image anymore, so release the memory
                image.release();
            }
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private void calculationThread()
    {
        TargetData data = null;
        try
        {
            while (!Thread.interrupted())
            {
                synchronized (dataLock)
                {
                    while (targetData == data)
                    {
                        dataLock.wait();
                    }
                    data = targetData;
                }
                String dataString = "";
                if (data != null)
                {
                    RelativePose pose = calculateRelativePose(data);
                    dataString = gson.toJson(pose);
                }
                visionData.setString(dataString);
                // If fps counter is enabled, calculate fps
                // TODO: Measure fps even if data is null, since null data isn't fresh, so the fps seems to drop.
                if (MEASURE_FPS)
                {
                    measureFps();
                }
            }
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private void processImage(VisionTargetPipeline pipeline)
    {
        // If the resolution changed, update the camera data network tables entry
        if (width != pipeline.getInput().width() || height != pipeline.getInput().height())
        {
            width = pipeline.getInput().width();
            height = pipeline.getInput().height();
            cameraData.setDoubleArray(new double[] { width, height });
            double focalLengthX = (width / 2.0) / (Math.tan(Math.toRadians(CAMERA_FOV_X / 2.0)));
            double focalLengthY = (height / 2.0) / (Math.tan(Math.toRadians(CAMERA_FOV_Y / 2.0)));
            focalLength = (focalLengthX + focalLengthY) / 2.0;
            if (cameraMat == null)
            {
                cameraMat = new Mat(3, 3, CvType.CV_32FC1);
            }
            // TODO: Should this be separate x and y focal lengths, or the average? test.
            cameraMat.put(0, 0, focalLengthX, 0, width / 2.0, 0, focalLengthY, height / 2.0, 0, 0, 1);
        }
        // Get the selected target from the pipeline
        TargetData data = pipeline.getSelectedTarget();
        synchronized (dataLock)
        {
            this.targetData = data;
            dataLock.notify();
        }

        // If debug display is enabled, render it
        if (DEBUG_DISPLAY == DebugDisplayType.BOUNDING_BOX || DEBUG_DISPLAY == DebugDisplayType.MASK
            || DEBUG_DISPLAY == DebugDisplayType.REGULAR)
        {
            debugDisplay(pipeline);
        }
    }

    private void debugDisplay(VisionTargetPipeline pipeline)
    {
        Mat image;
        Scalar color = new Scalar(0, 255, 0);
        if (DEBUG_DISPLAY == DebugDisplayType.MASK)
        {
            image = pipeline.getHslThresholdOutput();
            color = new Scalar(255);
        }
        else
        {
            image = pipeline.getInput();
        }
        if (DEBUG_DISPLAY == DebugDisplayType.BOUNDING_BOX)
        {
            image = image.clone();
            for (TargetData data : pipeline.getDetectedTargets())
            {
                if (data != null)
                {
                    int minX = data.x - data.w / 2;
                    int maxX = data.x + data.w / 2;
                    int minY = data.y - data.h / 2;
                    int maxY = data.y + data.h / 2;
                    Imgproc.rectangle(image, new Point(minX, minY), new Point(maxX, maxY), color, 2);
                }
            }
        }
        dashboardDisplay.putFrame(image);
    }

    private void measureFps()
    {
        numFrames++;
        double currTime = getTime();
        double elapsedTime = currTime - startTime;
        if (elapsedTime >= FPS_AVG_WINDOW)
        {
            double fps = (double) numFrames / elapsedTime;
            System.out.printf("Avg fps over %.3fsec: %.3f\n", elapsedTime, fps);
            numFrames = 0;
            startTime = currTime;
        }
    }

    private double getTime()
    {
        return (double) System.currentTimeMillis() / 1000;
    }

    private RelativePose calculateRelativePose(TargetData data)
    {
        // Calculate the corners of the left vision target
        Point[] leftPoints = new Point[4];
        data.leftTarget.rotatedRect.points(leftPoints);
        Point leftLeftCorner = Arrays.stream(leftPoints).min(Comparator.comparing(point -> point.x))
            .orElseThrow(IllegalStateException::new);
        Point leftRightCorner = Arrays.stream(leftPoints).max(Comparator.comparing(point -> point.x))
            .orElseThrow(IllegalStateException::new);
        Point leftBottomCorner = Arrays.stream(leftPoints).max(Comparator.comparing(point -> point.y))
            .orElseThrow(IllegalStateException::new);
        Point leftTopCorner = Arrays.stream(leftPoints).min(Comparator.comparing(point -> point.y))
            .orElseThrow(IllegalStateException::new);

        // Calculate the corners of the right vision target
        Point[] rightPoints = new Point[4];
        data.rightTarget.rotatedRect.points(rightPoints);
        Point rightLeftCorner = Arrays.stream(rightPoints).min(Comparator.comparing(point -> point.x))
            .orElseThrow(IllegalStateException::new);
        Point rightRightCorner = Arrays.stream(rightPoints).max(Comparator.comparing(point -> point.x))
            .orElseThrow(IllegalStateException::new);
        Point rightBottomCorner = Arrays.stream(rightPoints).max(Comparator.comparing(point -> point.y))
            .orElseThrow(IllegalStateException::new);
        Point rightTopCorner = Arrays.stream(rightPoints).min(Comparator.comparing(point -> point.y))
            .orElseThrow(IllegalStateException::new);

        // Assemble the calculated points into a matrix
        imagePoints.fromArray(leftLeftCorner, leftRightCorner, leftBottomCorner, leftTopCorner, rightLeftCorner,
            rightRightCorner, rightBottomCorner, rightTopCorner);

        // Use the black magic of the Ancient Ones to get the rotation and translation vectors
        Calib3d.solvePnP(worldPoints, imagePoints, cameraMat, dist, rotationVector, translationVector);
        // Get the distances in the x and z axes. (or in robot space, x and y)
        double x = translationVector.get(0, 0)[0];
        double z = translationVector.get(2, 0)[0];
        // Convert x,y to r,theta
        double distance = Math.sqrt(x * x + z * z);
        double heading = Math.toDegrees(Math.atan2(x, z));

        // Convert the axis-angle rotation vector into a rotation matrix
        Calib3d.Rodrigues(rotationVector, rotationMatrix);

        // Create the projection matrix
        Core.hconcat(Arrays.asList(rotationMatrix, translationVector), projectionMatrix);

        // Decompose the projection matrix to get the euler angles of rotation
        Calib3d.decomposeProjectionMatrix(projectionMatrix, dummyCameraMatrix, dummyRotMatrix, dummyTransVect,
            dummyRotMatX, dummyRotMatY, dummyRotMatZ, eulerAngles);
        double objectYaw = eulerAngles.get(1, 0)[0];

        // Write to the debug display, if necessary
        if (DEBUG_DISPLAY == DebugDisplayType.FULL_PNP || DEBUG_DISPLAY == DebugDisplayType.CORNERS)
        {
            Mat image = pipeline.getInput().clone();

            // Draw the contours first, so the corners get put on top
            if (DEBUG_DISPLAY == DebugDisplayType.FULL_PNP)
            {
                contourPoints.fromArray(data.leftTarget.contour.toArray());
                Imgproc.drawContours(image, Arrays.asList(contourPoints), 0, new Scalar(255, 0, 255), 2);
                contourPoints.fromArray(data.rightTarget.contour.toArray());
                Imgproc.drawContours(image, Arrays.asList(contourPoints), 0, new Scalar(255, 0, 255), 2);
            }

            // Draw the left and right target corners
            Imgproc.circle(image, leftLeftCorner, 1, new Scalar(0, 0, 255), 2);
            Imgproc.circle(image, leftRightCorner, 1, new Scalar(0, 255, 0), 2);
            Imgproc.circle(image, leftBottomCorner, 1, new Scalar(255, 0, 0), 2);
            Imgproc.circle(image, leftTopCorner, 1, new Scalar(0, 255, 255), 2);

            Imgproc.circle(image, rightLeftCorner, 1, new Scalar(0, 0, 255), 2);
            Imgproc.circle(image, rightRightCorner, 1, new Scalar(0, 255, 0), 2);
            Imgproc.circle(image, rightBottomCorner, 1, new Scalar(255, 0, 0), 2);
            Imgproc.circle(image, rightTopCorner, 1, new Scalar(0, 255, 255), 2);

            if (DEBUG_DISPLAY == DebugDisplayType.FULL_PNP)
            {
                // Project the XYZ axes out and draw the lines to show orientation
                Point origin = new Point(data.x, data.y);
                projectAndDrawAxes(image, origin, new Point3(10, 0, 0), rotationVector, translationVector, cameraMat,
                    dist, new Scalar(0, 0, 255));
                projectAndDrawAxes(image, origin, new Point3(0, 10, 0), rotationVector, translationVector, cameraMat,
                    dist, new Scalar(0, 255, 0));
                projectAndDrawAxes(image, origin, new Point3(0, 0, 10), rotationVector, translationVector, cameraMat,
                    dist, new Scalar(255, 0, 0));
            }

            dashboardDisplay.putFrame(image);
            image.release();
        }

        return new RelativePose(heading, distance, objectYaw);
    }

    private void projectAndDrawAxes(Mat image, Point origin, Point3 point, Mat rotationVector, Mat translationVector,
        Mat cameraMat, MatOfDouble dist, Scalar color)
    {
        pointToProject.fromArray(point);
        Calib3d.projectPoints(pointToProject, rotationVector, translationVector, cameraMat, dist, projectedPoints);
        Imgproc.line(image, origin, projectedPoints.toArray()[0], color, 2);
    }

    private class RelativePose
    {
        public double heading;
        public double distance;
        public double objectYaw;

        public RelativePose(double heading, double distance, double objectYaw)
        {
            this.heading = heading;
            this.distance = distance;
            this.objectYaw = objectYaw;
        }
    }
}

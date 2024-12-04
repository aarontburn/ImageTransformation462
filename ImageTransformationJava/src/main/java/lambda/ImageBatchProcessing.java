package lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import saaf.Inspector;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.*;
import java.util.*;
import java.util.List;

import static lambda.Constants.*;


public class ImageBatchProcessing implements RequestHandler<HashMap<String, Object>, HashMap<String, Object>> {

    private static boolean isColdStart = true; // Tracks whether it's a cold start
    private static final String OPERATIONS_KEY = "operations";

    private final Map<String, ImageProcessFunction> FUNCTIONS = new HashMap<>();


    /***
     *  Some testing code. This definitely doesn't work anymore after things were moved to S3.
     *
     *  @param args Command line arguments.
     */
    public static void main(final String[] args) {
        System.out.println("PWD: " + System.getProperty("user.dir") + "\n");

        final String testImageName = "sample image.jpg";
        final HashMap<String, Object> req = new HashMap<>();


        final File file = new File("../sample images/" + testImageName);
        try (FileInputStream fileInputStreamReader = new FileInputStream(file)) {
            final byte[] bytes = new byte[(int) file.length()];
            fileInputStreamReader.read(bytes);
            req.put(IMAGE_FILE_KEY, Base64.getEncoder().encodeToString(bytes));


            final Object[][] operations = new Object[][]{new Object[]{"details", new HashMap<>()}, new Object[]{"brightness", Constants.hashmapFromArray(new Object[][]{new Object[]{"brightness_delta", 50}})}, new Object[]{"rotate", Constants.hashmapFromArray(new Object[][]{new Object[]{"rotation_angle", 180}})}, new Object[]{"resize", Constants.hashmapFromArray(new Object[][]{new Object[]{"target_width", 250}, new Object[]{"target_height", 500}})}, new Object[]{"grayscale", new HashMap<>()}, new Object[]{"details", new HashMap<>()}, new Object[]{"transform", Constants.hashmapFromArray(new Object[][]{new Object[]{"output_format", "PNG"}})},};
            req.put(OPERATIONS_KEY, operations);

            final HashMap<String, Object> responseBody = new ImageBatchProcessing().handleRequest(req, null);

            if (responseBody.containsKey(ERROR_KEY)) {
                System.out.println(responseBody);
            } else {
                System.out.println(responseBody.get("operation_outputs"));
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) responseBody.get(IMAGE_FILE_KEY))));

                File outputFile = new File("test output.png");
                ImageIO.write(image, "png", outputFile);
                System.out.println("Image saved to: " + outputFile.getAbsolutePath());
            }


        } catch (final Exception e) {
            e.printStackTrace();
        }

    }


    /***
     *  Constructor; just populates a function map.
     */
    public ImageBatchProcessing() {
        FUNCTIONS.put("details", this::imageDetails);
        FUNCTIONS.put("rotate", this::imageRotate);
        FUNCTIONS.put("resize", this::imageResize);
        FUNCTIONS.put("grayscale", this::imageGrayscale);
        FUNCTIONS.put("brightness", this::imageBrightness);
        FUNCTIONS.put("transform", this::imageTransform);
    }


    /***
     *  The function to handle batch requests.
     *
     *  @param request The Lambda Function input
     *  @param context The Lambda execution environment context object.
     *  @return A HashMap containing request output.
     */
    public HashMap<String, Object> handleRequest(final HashMap<String, Object> request, final Context context) {
        Inspector inspector = new Inspector();
        inspector.addAttribute(COLD_START_KEY, isColdStart ? 1 : 0);
        isColdStart = false; // Reset the cold start flag for subsequent invocations

        final String validateMessage = Constants.validateRequestMap(request, BUCKET_KEY, FILE_NAME_KEY, OPERATIONS_KEY);
        if (validateMessage != null) {
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, validateMessage);
            return inspector.finish();
        }

        try {
            final String bucketName = (String) request.get(BUCKET_KEY);
            final String fileName = (String) request.get(FILE_NAME_KEY);
            final Object[][] operations = (Object[][]) request.get(OPERATIONS_KEY);
            final String outputFileName = "batch_" + fileName;

            final long processingStartTime = System.currentTimeMillis();

            final List<HashMap<String, Object>> operationsOutput = new ArrayList<>();


            BufferedImage image = ImageIO.read(Constants.getImageFromS3AndRecordLatency(bucketName, fileName, inspector));
            if (image == null) {
                throw new IllegalArgumentException("Failed to decode image data.");
            }

            for (int i = 0; i < operations.length; i++) {
                final String operationName = (String) operations[i][0];
                final HashMap<String, Object> operationArgs = (HashMap<String, Object>) operations[i][1];

                if (!FUNCTIONS.containsKey(operationName)) {
                    System.out.println("Pipeline error: Invalid operation name at index " + i + ": " + operationName);
                    continue;
                }
                if (operationArgs == null) {
                    System.out.println("Pipeline error: Error retrieving input arguments at index " + i);
                    continue;
                }

                System.out.println("Executing function " + i + ": " + operationName);
                final ImageProcessFunction operationFunction = FUNCTIONS.get(operationName);
                final Map<String, Object> responseObject = operationFunction.Process(image, operationArgs, context);

                if (responseObject.containsKey(ERROR_KEY)) {
                    System.out.println("Pipeline error: Error executing function at index " + i);
                }

                image = responseObject.containsKey(IMAGE_FILE_KEY) ? (BufferedImage) responseObject.get(IMAGE_FILE_KEY) : image;
                final HashMap<String, Object> appendedOutput = new HashMap<>(responseObject);
                appendedOutput.remove(IMAGE_FILE_KEY);
                operationsOutput.add(appendedOutput);
            }


            final boolean successfulWriteToS3 = Constants.saveImageToS3(bucketName, outputFileName, "png", image);
            if (!successfulWriteToS3) {
                throw new RuntimeException("Could not write image to S3");
            }

            inspector.addAttribute("operation_outputs", operationsOutput);
            inspector.addAttribute(IMAGE_URL_KEY, getDownloadableImageURL(bucketName, outputFileName));
            inspector.addAttribute(IMAGE_URL_EXPIRES_IN, IMAGE_URL_EXPIRATION_SECONDS);

            final long functionRunTime = System.currentTimeMillis() - processingStartTime;
            inspector.addAttribute(FUNCTION_RUN_TIME_KEY, functionRunTime);
            inspector.addAttribute(ESTIMATED_COST_KEY, estimateCost(functionRunTime));

        } catch (final Exception e) {
            e.printStackTrace();
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, e.toString());
        }
        return inspector.finish();
    }


    /***
     *  Function #1: Image upload and validation
     *
     *  @param request  The image arguments.
     *  @param context  The AWS Lambda context.
     *  @return A response object.
     */
    public HashMap<String, Object> imageDetails(final HashMap<String, Object> request, final Context context) {
        return imageDetails(null, request, context);
    }

    /***
     *  Function 2: Image Rotation
     *
     *  @param request  The image arguments.
     *  @param context  The AWS Lambda context.
     *  @return A response object.
     */
    public HashMap<String, Object> imageRotate(final HashMap<String, Object> request, final Context context) {
        return imageRotate(null, request, context);
    }

    /***
     *  Function 3: Image Resize
     *
     *  @param request  The image arguments.
     *  @param context  The AWS Lambda context.
     *  @return A response object.
     */
    public HashMap<String, Object> imageResize(final HashMap<String, Object> request, final Context context) {
        return imageResize(null, request, context);
    }

    /***
     *  Function 4: Image Grayscale
     *
     *  @param request  The image arguments.
     *  @param context  The AWS Lambda context.
     *  @return A response object.
     */
    public HashMap<String, Object> imageGrayscale(final HashMap<String, Object> request, final Context context) {
        return imageGrayscale(null, request, context);
    }

    /***
     *  Function 5: Image Brightness
     *
     *  @param request  The image arguments.
     *  @param context  The AWS Lambda context.
     *  @return A response object.
     */
    public HashMap<String, Object> imageBrightness(final HashMap<String, Object> request, final Context context) {
        return imageBrightness(null, request, context);
    }

    /***
     *  Function 6: Image Transform
     *
     *  @param request  The image arguments.
     *  @param context  The AWS Lambda context.
     *  @return A response object.
     */
    public HashMap<String, Object> imageTransform(final HashMap<String, Object> request, final Context context) {
        return imageTransform(null, request, context);
    }


    /***
     *  Function #1: Image Details Batch Method.
     *      This function should only be called by the batch handler, which passes in a buffered image to use.
     *
     *  @param image    The image to get the details of.
     *  @param request  The request arguments.
     *  @param context  The AWS Lambda Context.
     *  @return A response object.
     */
    private HashMap<String, Object> imageDetails(final BufferedImage image, final HashMap<String, Object> request, final Context context) {
        final boolean isBatch = image != null;

        // This could be replaced with a hashmap, especially if we don't need info from the inspector
        Inspector inspector = new Inspector();
        inspector.addAttribute(COLD_START_KEY, isColdStart ? 1 : 0);
        isColdStart = false; // Reset the cold start flag for subsequent invocations

        final String validateMessage = Constants.validateRequestMap(request, BUCKET_KEY, FILE_NAME_KEY);
        if (validateMessage != null) {
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, validateMessage);
            return inspector.finish();
        }

        final long processingStartTime = System.currentTimeMillis();


        try {
            final String bucketName = (String) request.get(BUCKET_KEY);
            final String fileName = (String) request.get(FILE_NAME_KEY);

            final InputStream objectData = Constants.getImageFromS3AndRecordLatency(bucketName, fileName, inspector);
            final BufferedImage imageObject = isBatch ? image : ImageIO.read(objectData);

            inspector.addAttribute("region", System.getenv().getOrDefault("AWS_REGION", "NO_REGION_DATA"));
            inspector.addAttribute("width", imageObject.getWidth());
            inspector.addAttribute("height", imageObject.getHeight());
            inspector.addAttribute("mode", getColorType(imageObject.getColorModel().getColorSpace().getType()));
            inspector.addAttribute("has_transparency_data", imageObject.getColorModel().hasAlpha() ? 1 : 0);
            if (isBatch) {
                inspector.addAttribute(IMAGE_FILE_KEY, imageObject);
            }
            final long functionRunTime = System.currentTimeMillis() - processingStartTime;
            inspector.addAttribute(FUNCTION_RUN_TIME_KEY, functionRunTime);
            inspector.addAttribute(ESTIMATED_COST_KEY, estimateCost(functionRunTime));

        } catch (final Exception e) {
            e.printStackTrace();
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, e.toString());
        }

        return inspector.finish();
    }


    /***
     *  Function #2: Rotation Batch Method.
     *      This function should only be called by the batch handler, which passes in a buffered image to use.
     *
     *  @param image    The image to rotate.
     *  @param request  The request arguments.
     *  @param context  The AWS Lambda Context
     *  @return A response object.
     */
    private HashMap<String, Object> imageRotate(final BufferedImage image, final HashMap<String, Object> request, final Context context) {
        final boolean isBatch = image != null;

        Inspector inspector = new Inspector();
        inspector.addAttribute(COLD_START_KEY, isColdStart ? 1 : 0);
        isColdStart = false; // Reset the cold start flag for subsequent invocations

        final String validateMessage = Constants.validateRequestMap(request, BUCKET_KEY, FILE_NAME_KEY, "rotation_angle");
        if (validateMessage != null) {
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, validateMessage);
            return inspector.finish();
        }

        try {
            // Extract input parameters
            final String bucketName = (String) request.get(BUCKET_KEY);
            final String fileName = (String) request.get(FILE_NAME_KEY);
            final Integer rotationAngle = (Integer) request.get("rotation_angle");
            final String outputFileName = "rotated_" + fileName;

            final long processingStartTime = System.currentTimeMillis();

            final BufferedImage originalImage = isBatch ? image : ImageIO.read(Constants.getImageFromS3AndRecordLatency(bucketName, fileName, inspector));
            if (originalImage == null) {
                throw new IllegalArgumentException("Failed to decode image data.");
            }

            // Validate rotation angle
            if (!(rotationAngle == 90 || rotationAngle == 180 || rotationAngle == 270)) {
                throw new IllegalArgumentException("Invalid rotation angle. Only 90, 180, or 270 degrees are supported.");
            }

            // Perform rotation
            final BufferedImage rotatedImage = rotateImage(originalImage, rotationAngle);

            if (!isBatch) {
                final boolean successfulWriteToS3 = Constants.saveImageToS3(bucketName, outputFileName, "png", rotatedImage);
                if (!successfulWriteToS3) {
                    throw new RuntimeException("Could not write image to S3");
                }
            }


            // Populate response attributes
            inspector.addAttribute("original_width", originalImage.getWidth());
            inspector.addAttribute("original_height", originalImage.getHeight());
            inspector.addAttribute("rotated_width", rotatedImage.getWidth());
            inspector.addAttribute("rotated_height", rotatedImage.getHeight());
            inspector.addAttribute("rotation_angle", rotationAngle);
            inspector.addAttribute("rotated_image_key", outputFileName);
            if (isBatch) {
                inspector.addAttribute(IMAGE_FILE_KEY, rotatedImage);
            } else {
                inspector.addAttribute(IMAGE_URL_KEY, getDownloadableImageURL(bucketName, outputFileName));
                inspector.addAttribute(IMAGE_URL_EXPIRES_IN, IMAGE_URL_EXPIRATION_SECONDS);
            }

            final long functionRunTime = System.currentTimeMillis() - processingStartTime;
            inspector.addAttribute(FUNCTION_RUN_TIME_KEY, functionRunTime);
            inspector.addAttribute(ESTIMATED_COST_KEY, estimateCost(functionRunTime));

        } catch (final Exception e) {
            e.printStackTrace();
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, e.getMessage());
        }

        return inspector.finish();
    }

    /***
     *  Helper method for image rotation.
     *
     *  @param image            The image to rotate.
     *  @param rotationAngle    The rotation angle.
     *  @return The rotated angle.
     */
    private BufferedImage rotateImage(final BufferedImage image, final int rotationAngle) {
        final int width = image.getWidth();
        final int height = image.getHeight();

        // Create a new BufferedImage with appropriate dimensions and type
        final BufferedImage rotatedImage = new BufferedImage((rotationAngle == 90 || rotationAngle == 270) ? height : width, (rotationAngle == 90 || rotationAngle == 270) ? width : height, BufferedImage.TYPE_INT_ARGB);

        final Graphics2D graphics = rotatedImage.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Perform rotation with proper translation
        if (rotationAngle == 90) {
            graphics.translate(height, 0);
        } else if (rotationAngle == 180) {
            graphics.translate(width, height);
        } else if (rotationAngle == 270) {
            graphics.translate(0, width);
        }

        graphics.rotate(Math.toRadians(rotationAngle));
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();

        return rotatedImage;
    }


    /***
     *  Function #3: Image Resize Batch Method.
     *      This function should only be called by the batch handler, which passes in a buffered image to use.
     *
     *  @param image    The image to modify.
     *  @param request  The request arguments.
     *  @param context  The AWS Lambda Context
     *  @return A response object.
     */
    private HashMap<String, Object> imageResize(final BufferedImage image, final HashMap<String, Object> request, final Context context) {

        final boolean isBatch = image != null;

        Inspector inspector = new Inspector();
        inspector.addAttribute(COLD_START_KEY, isColdStart ? 1 : 0);
        isColdStart = false; // Reset the cold start flag for subsequent invocations

        final String validateMessage = Constants.validateRequestMap(request, BUCKET_KEY, FILE_NAME_KEY, "target_width", "target_height");

        if (validateMessage != null) {
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, validateMessage);
            return inspector.finish();
        }

        try {
            // Extract inputs from the request
            final String bucketName = (String) request.get(BUCKET_KEY);
            final String fileName = (String) request.get(FILE_NAME_KEY);
            final Integer targetWidth = (Integer) request.get("target_width");
            final Integer targetHeight = (Integer) request.get("target_height");
            final String outputFileName = "resized_" + fileName;


            final long processingStartTime = System.currentTimeMillis();


            if (targetWidth <= 0 || targetHeight <= 0) {
                throw new IllegalArgumentException("Target dimensions must be positive integers.");
            }

            final BufferedImage originalImage = isBatch ? image : ImageIO.read(Constants.getImageFromS3AndRecordLatency(bucketName, fileName, inspector));

            // Resize the image
            final Image resizedImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
            final BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, originalImage.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : originalImage.getType());
            outputImage.getGraphics().drawImage(resizedImage, 0, 0, null);

            if (!isBatch) {
                final boolean successfulWriteToS3 = Constants.saveImageToS3(bucketName, outputFileName, "png", outputImage);
                if (!successfulWriteToS3) {
                    throw new RuntimeException("Could not write image to S3");
                }
            }


            // Add resized image details to the response
            inspector.addAttribute("message", "Image resized successfully.");
            inspector.addAttribute("target_width", targetWidth);
            inspector.addAttribute("target_height", targetHeight);
            if (isBatch) {
                inspector.addAttribute(IMAGE_FILE_KEY, outputImage);
            } else {
                inspector.addAttribute(IMAGE_URL_KEY, getDownloadableImageURL(bucketName, outputFileName));
                inspector.addAttribute(IMAGE_URL_EXPIRES_IN, IMAGE_URL_EXPIRATION_SECONDS);
            }

            final long functionRunTime = System.currentTimeMillis() - processingStartTime;
            inspector.addAttribute(FUNCTION_RUN_TIME_KEY, functionRunTime);
            inspector.addAttribute(ESTIMATED_COST_KEY, estimateCost(functionRunTime));

        } catch (Exception e) {
            e.printStackTrace();
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, e.getMessage());
        }

        return inspector.finish();
    }


    /***
     *  Function #4: Grayscale Batch Method.
     *      This function should only be called by the batch handler, which passes in a buffered image to use.
     *
     *  @param image    The image to modify.
     *  @param request  The request arguments.
     *  @param context  The AWS Lambda Context
     *  @return A response object.
     */
    private HashMap<String, Object> imageGrayscale(final BufferedImage image, final HashMap<String, Object> request, final Context context) {

        final boolean isBatch = image != null;

        Inspector inspector = new Inspector();
        inspector.addAttribute(COLD_START_KEY, isColdStart ? 1 : 0);
        isColdStart = false; // Reset the cold start flag for subsequent invocations

        final String validateMessage = Constants.validateRequestMap(request, BUCKET_KEY, FILE_NAME_KEY);
        if (validateMessage != null) {
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, validateMessage);
            return inspector.finish();
        }

        try {
            final String bucketName = (String) request.get(BUCKET_KEY);
            final String fileName = (String) request.get(FILE_NAME_KEY);
            final String outputFileName = "grayscaled_" + fileName;


            final long processingStartTime = System.currentTimeMillis();

            final BufferedImage originalImage = isBatch ? image : ImageIO.read(Constants.getImageFromS3AndRecordLatency(bucketName, fileName, inspector));

            // Convert the image to grayscale
            final BufferedImage grayscaleImage = new BufferedImage(originalImage.getWidth(), originalImage.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            grayscaleImage.getGraphics().drawImage(originalImage, 0, 0, null);

            if (!isBatch) {
                final boolean successfulWriteToS3 = Constants.saveImageToS3(bucketName, outputFileName, "png", grayscaleImage);
                if (!successfulWriteToS3) {
                    throw new RuntimeException("Could not write image to S3");
                }
            }

            // Populate response attributes
            inspector.addAttribute("original_width", originalImage.getWidth());
            inspector.addAttribute("original_height", originalImage.getHeight());
            if (isBatch) {
                inspector.addAttribute(IMAGE_FILE_KEY, grayscaleImage);
            } else {
                inspector.addAttribute(IMAGE_URL_KEY, getDownloadableImageURL(bucketName, outputFileName));
                inspector.addAttribute(IMAGE_URL_EXPIRES_IN, IMAGE_URL_EXPIRATION_SECONDS);
            }

            final long functionRunTime = System.currentTimeMillis() - processingStartTime;
            inspector.addAttribute(FUNCTION_RUN_TIME_KEY, functionRunTime);
            inspector.addAttribute(ESTIMATED_COST_KEY, estimateCost(functionRunTime));

        } catch (final Exception e) {
            e.printStackTrace();
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, e.getMessage());
        }

        return inspector.finish();
    }


    /***
     *  The minimum brightness value.
     */
    private static final int MIN_BRIGHTNESS = 1;

    /***
     *  The maximum brightness value.
     */
    private static final int MAX_BRIGHTNESS = 100;

    /***
     *  Function #5: Brightness Batch Method.
     *      This function should only be called by the batch handler, which passes in a buffered image to use.
     *
     *  @param image    The image to modify.
     *  @param request  The request arguments.
     *  @param context  The AWS Lambda Context
     *  @return A response object.
     */
    private HashMap<String, Object> imageBrightness(final BufferedImage image, final HashMap<String, Object> request, final Context context) {
        final boolean isBatch = image != null;

        Inspector inspector = new Inspector();
        inspector.addAttribute(COLD_START_KEY, isColdStart ? 1 : 0);
        isColdStart = false; // Reset the cold start flag for subsequent invocations


        final String validateMessage = Constants.validateRequestMap(request, BUCKET_KEY, FILE_NAME_KEY, "brightness_delta");
        if (validateMessage != null) {
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, validateMessage);
            return inspector.finish();
        }


        try {
            // Extract input parameters
            final String bucketName = (String) request.get(BUCKET_KEY);
            final String fileName = (String) request.get(FILE_NAME_KEY);
            final Integer brightnessDelta = (Integer) request.get("brightness_delta");
            final String outputFileName = "brightness_" + fileName;

            final long processingStartTime = System.currentTimeMillis();

            // Validate brightness_delta
            if (brightnessDelta < MIN_BRIGHTNESS || brightnessDelta > MAX_BRIGHTNESS) {
                throw new IllegalArgumentException(String.format("Invalid brightness_delta. Must be between %d and %d.", MIN_BRIGHTNESS, MAX_BRIGHTNESS));
            }

            // Map brightness_delta (1–100) to RescaleOp factor (0.0–2.0)
            final float brightnessFactor = brightnessDelta / 50.0f;

            // Decode the Base64-encoded image
            final BufferedImage originalImage = isBatch ? image : ImageIO.read(Constants.getImageFromS3AndRecordLatency(bucketName, fileName, inspector));

            // Adjust brightness
            final BufferedImage brightenedImage = adjustBrightness(originalImage, brightnessFactor);

            if (!isBatch) {
                final boolean successfulWriteToS3 = Constants.saveImageToS3(bucketName, outputFileName, "png", brightenedImage);
                if (!successfulWriteToS3) {
                    throw new RuntimeException("Could not write image to S3");

                }
            }

            // Populate response attributes
            inspector.addAttribute("original_width", originalImage.getWidth());
            inspector.addAttribute("original_height", originalImage.getHeight());
            inspector.addAttribute("brightness_delta", brightnessDelta);
            inspector.addAttribute("brightness_factor", brightnessFactor);
            if (isBatch) {
                inspector.addAttribute(IMAGE_FILE_KEY, brightenedImage);
            } else {
                inspector.addAttribute(IMAGE_URL_KEY, getDownloadableImageURL(bucketName, outputFileName));
                inspector.addAttribute(IMAGE_URL_EXPIRES_IN, IMAGE_URL_EXPIRATION_SECONDS);
            }

            final long functionRunTime = System.currentTimeMillis() - processingStartTime;
            inspector.addAttribute(FUNCTION_RUN_TIME_KEY, functionRunTime);
            inspector.addAttribute(ESTIMATED_COST_KEY, estimateCost(functionRunTime));

        } catch (Exception e) {
            e.printStackTrace();
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, e.getMessage());
        }

        return inspector.finish();
    }


    /**
     * Helper function to adjust brightness.
     *
     * @param image            The original image to modify.
     * @param brightnessFactor The factor to adjust the brightness (1.0 = original, < 1.0 = darker, > 1.0 = brighter).
     * @return The modified image with adjusted brightness.
     */
    private BufferedImage adjustBrightness(final BufferedImage image, final float brightnessFactor) {
        final RescaleOp rescaleOp = new RescaleOp(brightnessFactor, 0, null);
        final BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
        final Graphics2D g = result.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        rescaleOp.filter(result, result);
        return result;
    }


    /***
     *  Function #6: Brightness Batch Method.
     *      This function should only be called by the batch handler, which passes in a buffered image to use.
     *
     *  @param image    The image to modify.
     *  @param request  The request arguments.
     *  @param context  The AWS Lambda Context
     *  @return A response object.
     */
    private HashMap<String, Object> imageTransform(final BufferedImage image, final HashMap<String, Object> request, final Context context) {
        final boolean isBatch = image != null;

        Inspector inspector = new Inspector();
        inspector.addAttribute(COLD_START_KEY, isColdStart ? 1 : 0);
        isColdStart = false; // Reset the cold start flag for subsequent invocations

        final String validateMessage = Constants.validateRequestMap(request, BUCKET_KEY, FILE_NAME_KEY, "target_format");
        if (validateMessage != null) {
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, validateMessage);
            return inspector.finish();
        }

        try {
            // Extract input parameter
            final String bucketName = (String) request.get(BUCKET_KEY);
            final String fileName = (String) request.get(FILE_NAME_KEY);
            final String targetFormat = (String) request.get("target_format");
            final String outputFileName = "transformed_" + fileName;


            final long processingStartTime = System.currentTimeMillis();

            final BufferedImage originalImage = ImageIO.read(Constants.getImageFromS3AndRecordLatency(bucketName, fileName, inspector));

            // Transform image to the target format
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            if (!ImageIO.write(originalImage, targetFormat, outputStream)) {
                throw new IllegalArgumentException("Unsupported target format: " + targetFormat);
            }

            final BufferedImage transformedImage = ImageIO.read(new ByteArrayInputStream(outputStream.toByteArray()));

            if (!isBatch) {
                final boolean successfulWriteToS3 = Constants.saveImageToS3(bucketName, outputFileName, "png", transformedImage);
                if (!successfulWriteToS3) {
                    throw new RuntimeException("Could not write image to S3");
                }
            }


            // Populate response attributes
            inspector.addAttribute("original_width", originalImage.getWidth());
            inspector.addAttribute("original_height", originalImage.getHeight());
            inspector.addAttribute("target_format", targetFormat);
            if (isBatch) {
                inspector.addAttribute(IMAGE_FILE_KEY, transformedImage);
            } else {
                inspector.addAttribute(IMAGE_URL_KEY, getDownloadableImageURL(bucketName, outputFileName));
                inspector.addAttribute(IMAGE_URL_EXPIRES_IN, IMAGE_URL_EXPIRATION_SECONDS);
            }

            final long functionRunTime = System.currentTimeMillis() - processingStartTime;
            inspector.addAttribute(FUNCTION_RUN_TIME_KEY, functionRunTime);
            inspector.addAttribute(ESTIMATED_COST_KEY, estimateCost(functionRunTime));


        } catch (Exception e) {
            e.printStackTrace();
            inspector = new Inspector();
            inspector.addAttribute(ERROR_KEY, e.getMessage());
        }

        return inspector.finish();
    }


}

package com.limelight.sbs;

/** Compute shaders for the ZipDepth-calibrated, host-style contractive disparity field. */
final class ClientSbsGpuDisparityShaders {
    static final float CONTAINER_LIMIT = ClientSbsV2CoordinateContract.CONTAINER_LIMIT;
    static final float MAX_VERTICAL_SHEAR = 2.0f;
    static final float VERTICAL_MAJORANT_SHARE = 0.75f;
    static final float MAX_HORIZONTAL_SLOPE = 0.5f;

    private ClientSbsGpuDisparityShaders() {
    }

    static String verticalForward(int width, int height) {
        validateSize(width, height);
        return header(width, height)
                + "uniform highp sampler2D uDepthTexture;\n"
                + "uniform highp sampler2D uProfileTexture;\n"
                + "uniform float uInverseRawCoordinateScale;\n"
                + "layout(rgba32f, binding = 0) uniform writeonly highp image2D "
                + "uEnvelopeScratch;\n"
                + rawV2CandidateFunctions()
                + "void storeEnvelope(int x, int y, float upper, float lower, "
                + "float candidate) {\n"
                + "  imageStore(uEnvelopeScratch, ivec2(x, y), "
                + "vec4(upper, lower, candidate, 0.0));\n"
                + "}\n"
                + "void main() {\n"
                + "  int x = int(gl_GlobalInvocationID.x);\n"
                + "  if (x >= FIELD_WIDTH) return;\n"
                + "  vec4 camera = texelFetch(uProfileTexture, ivec2(0, 0), 0);\n"
                + "  bool ready = camera.w > 0.5\n"
                + "      && !isnan(camera.x) && !isinf(camera.x)\n"
                + "      && !isnan(uInverseRawCoordinateScale)\n"
                + "      && !isinf(uInverseRawCoordinateScale)\n"
                + "      && uInverseRawCoordinateScale > 0.0;\n"
                + "  float step = " + floatLiteral(MAX_VERTICAL_SHEAR)
                + " / float(FIELD_WIDTH);\n"
                + "  float candidate = ready ? disparityCandidate(x, 0, camera.x, "
                + "uInverseRawCoordinateScale) : 0.0;\n"
                + "  float upper = candidate;\n"
                + "  float lower = candidate;\n"
                + "  storeEnvelope(x, 0, upper, lower, candidate);\n"
                + "  for (int y = 1; y < FIELD_HEIGHT; y++) {\n"
                + "    candidate = ready ? disparityCandidate(x, y, camera.x, "
                + "uInverseRawCoordinateScale) : 0.0;\n"
                + "    upper = max(candidate, upper - step);\n"
                + "    lower = min(candidate, lower + step);\n"
                + "    storeEnvelope(x, y, upper, lower, candidate);\n"
                + "  }\n"
                + "}\n";
    }

    static String verticalFinish(int width, int height) {
        validateSize(width, height);
        return header(width, height)
                + "uniform highp sampler2D uEnvelopeScratch;\n"
                + "layout(r32f, binding = 0) uniform writeonly highp image2D "
                + "uVerticalConditioned;\n"
                + "void storeConditioned(int x, int y, float backwardUpper, "
                + "float backwardLower) {\n"
                + "  vec2 forward = texelFetch(uEnvelopeScratch, ivec2(x, y), 0).rg;\n"
                + "  float upper = max(forward.x, backwardUpper);\n"
                + "  float lower = min(forward.y, backwardLower);\n"
                + "  float conditioned = " + floatLiteral(VERTICAL_MAJORANT_SHARE)
                + " * upper + " + floatLiteral(1.0f - VERTICAL_MAJORANT_SHARE)
                + " * lower;\n"
                + "  imageStore(uVerticalConditioned, ivec2(x, y), "
                + "vec4(conditioned, 0.0, 0.0, 1.0));\n"
                + "}\n"
                + "void main() {\n"
                + "  int x = int(gl_GlobalInvocationID.x);\n"
                + "  if (x >= FIELD_WIDTH) return;\n"
                + "  float step = " + floatLiteral(MAX_VERTICAL_SHEAR)
                + " / float(FIELD_WIDTH);\n"
                + "  int y = FIELD_HEIGHT - 1;\n"
                + "  float candidate = texelFetch(uEnvelopeScratch, ivec2(x, y), 0).b;\n"
                + "  float backwardUpper = candidate;\n"
                + "  float backwardLower = candidate;\n"
                + "  storeConditioned(x, y, backwardUpper, backwardLower);\n"
                + "  for (int reverseY = FIELD_HEIGHT - 2; reverseY >= 0; reverseY--) {\n"
                + "    candidate = texelFetch(uEnvelopeScratch, "
                + "ivec2(x, reverseY), 0).b;\n"
                + "    backwardUpper = max(candidate, backwardUpper - step);\n"
                + "    backwardLower = min(candidate, backwardLower + step);\n"
                + "    storeConditioned(x, reverseY, backwardUpper, backwardLower);\n"
                + "  }\n"
                + "}\n";
    }

    static String horizontalForward(int width, int height) {
        validateSize(width, height);
        return header(width, height)
                + "uniform highp sampler2D uVerticalConditioned;\n"
                + "layout(rgba32f, binding = 0) uniform writeonly highp image2D "
                + "uEnvelopeScratch;\n"
                + "void main() {\n"
                + "  int y = int(gl_GlobalInvocationID.x);\n"
                + "  if (y >= FIELD_HEIGHT) return;\n"
                + "  float step = " + floatLiteral(MAX_HORIZONTAL_SLOPE)
                + " / float(FIELD_WIDTH);\n"
                + "  float forward = texelFetch(uVerticalConditioned, ivec2(0, y), 0).r;\n"
                + "  imageStore(uEnvelopeScratch, ivec2(0, y), "
                + "vec4(forward, 0.0, 0.0, 0.0));\n"
                + "  for (int x = 1; x < FIELD_WIDTH; x++) {\n"
                + "    float candidate = texelFetch(uVerticalConditioned, "
                + "ivec2(x, y), 0).r;\n"
                + "    forward = max(candidate, forward - step);\n"
                + "    imageStore(uEnvelopeScratch, ivec2(x, y), "
                + "vec4(forward, 0.0, 0.0, 0.0));\n"
                + "  }\n"
                + "}\n";
    }

    static String horizontalFinish(int width, int height) {
        validateSize(width, height);
        return header(width, height)
                + "uniform highp sampler2D uVerticalConditioned;\n"
                + "uniform highp sampler2D uEnvelopeScratch;\n"
                + "layout(r32f, binding = 0) uniform writeonly highp image2D uFinalParallax;\n"
                + "void storeFinal(int x, int y, float backward) {\n"
                + "  float forward = texelFetch(uEnvelopeScratch, ivec2(x, y), 0).r;\n"
                + "  imageStore(uFinalParallax, ivec2(x, y), "
                + "vec4(max(forward, backward), 0.0, 0.0, 1.0));\n"
                + "}\n"
                + "void main() {\n"
                + "  int y = int(gl_GlobalInvocationID.x);\n"
                + "  if (y >= FIELD_HEIGHT) return;\n"
                + "  float step = " + floatLiteral(MAX_HORIZONTAL_SLOPE)
                + " / float(FIELD_WIDTH);\n"
                + "  int x = FIELD_WIDTH - 1;\n"
                + "  float backward = texelFetch(uVerticalConditioned, "
                + "ivec2(x, y), 0).r;\n"
                + "  storeFinal(x, y, backward);\n"
                + "  for (int reverseX = FIELD_WIDTH - 2; reverseX >= 0; reverseX--) {\n"
                + "    float candidate = texelFetch(uVerticalConditioned, "
                + "ivec2(reverseX, y), 0).r;\n"
                + "    backward = max(candidate, backward - step);\n"
                + "    storeFinal(reverseX, y, backward);\n"
                + "  }\n"
                + "}\n";
    }

    private static String header(int width, int height) {
        return "#version 310 es\n"
                + "precision highp float;\n"
                + "precision highp int;\n"
                + "layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;\n"
                + "const int FIELD_WIDTH = " + width + ";\n"
                + "const int FIELD_HEIGHT = " + height + ";\n";
    }

    private static String rawV2CandidateFunctions() {
        return "float hostV2Expm1(float value) {\n"
                + "  if (abs(value) < 1.0e-3) {\n"
                + "    float value2 = value * value;\n"
                + "    return value + 0.5 * value2 + value2 * value * (1.0 / 6.0);\n"
                + "  }\n"
                + "  return exp(value) - 1.0;\n"
                + "}\n"
                + "float hostV2Log1p(float value) {\n"
                + "  if (abs(value) < 1.0e-3) {\n"
                + "    float value2 = value * value;\n"
                + "    return value - 0.5 * value2 + value2 * value * (1.0 / 3.0);\n"
                + "  }\n"
                + "  return log(1.0 + value);\n"
                + "}\n"
                + "float hostV2Curve(float coordinate) {\n"
                + "  if (coordinate < 0.0) return "
                + floatLiteral(ClientSbsV2CoordinateContract.FAR_CURVE_SCALE)
                + " * hostV2Expm1(coordinate / "
                + floatLiteral(ClientSbsV2CoordinateContract.FAR_CURVE_SCALE) + ");\n"
                + "  if (coordinate <= 1.0) return coordinate;\n"
                + "  return 1.0 + "
                + floatLiteral(ClientSbsV2CoordinateContract.NEAR_CURVE_SCALE)
                + " * hostV2Log1p((coordinate - 1.0) / "
                + floatLiteral(ClientSbsV2CoordinateContract.NEAR_CURVE_SCALE) + ");\n"
                + "}\n"
                + "float pointwiseContainer(float requested) {\n"
                + "  if (isnan(requested) || isinf(requested)) return 0.0;\n"
                + "  float magnitude = abs(requested);\n"
                + "  float smaller = min(magnitude, " + floatLiteral(CONTAINER_LIMIT) + ");\n"
                + "  float larger = max(magnitude, " + floatLiteral(CONTAINER_LIMIT) + ");\n"
                + "  float ratio = smaller / larger;\n"
                + "  float ratioSquared = ratio * ratio;\n"
                + "  float containedMagnitude = smaller * inversesqrt(sqrt(\n"
                + "      1.0 + ratioSquared * ratioSquared));\n"
                + "  float contained = requested < 0.0 ? -containedMagnitude : containedMagnitude;\n"
                + "  return clamp(contained, -" + floatLiteral(CONTAINER_LIMIT) + ", "
                + floatLiteral(CONTAINER_LIMIT) + ");\n"
                + "}\n"
                + "float rawV2Parallax(float rawDepth, float shotMean,\n"
                + "    float inverseRawCoordinateScale) {\n"
                + "  if (isnan(rawDepth) || isinf(rawDepth)) return 0.0;\n"
                + "  float coordinate = (rawDepth - shotMean) * inverseRawCoordinateScale;\n"
                + "  float requested = "
                + floatLiteral(ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH)
                + " * " + floatLiteral(ClientSbsV2CoordinateContract.PARALLAX_PER_POP)
                + " * hostV2Curve(coordinate);\n"
                + "  return pointwiseContainer(requested);\n"
                + "}\n"
                + "float disparityCandidate(int x, int y, float shotMean,\n"
                + "    float inverseRawCoordinateScale) {\n"
                + "  float rawDepth = texelFetch(uDepthTexture, ivec2(x, y), 0).r;\n"
                + "  return rawV2Parallax(rawDepth, shotMean, inverseRawCoordinateScale);\n"
                + "}\n";
    }

    private static String floatLiteral(float value) {
        return String.format(java.util.Locale.US, "%.8f", value);
    }

    private static void validateSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Disparity field dimensions must be positive");
        }
    }
}

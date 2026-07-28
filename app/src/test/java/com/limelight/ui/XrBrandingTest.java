package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Guards the visible Sunshine 3D and Moonlight 3D brand pair. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class XrBrandingTest {
    private static final Pattern RETIRED_VISIBLE_NAME = Pattern.compile(
            "\\b(?:Apollo|Artemis|Artemistics)\\b");
    private static final Pattern NETTEST_SUCCESS = Pattern.compile(
            "<string\\s+name=\"nettest_text_success\"[^>]*>(.*?)</string>",
            Pattern.DOTALL);

    @Test
    public void localizedProductCopyUsesCurrentNames() throws IOException {
        File resourceRoot = new File("src/main/res");
        File[] directories = resourceRoot.listFiles(file ->
                file.isDirectory() && file.getName().startsWith("values"));
        assertTrue("Android resource directories are unavailable", directories != null);

        List<String> offenders = new ArrayList<>();
        for (File directory : directories) {
            File strings = new File(directory, "strings.xml");
            if (!strings.isFile()) {
                continue;
            }
            String source = read(strings);
            assertFalse("Visible host link points to the upstream project",
                    source.contains("https://github.com/ClassicOldSong/Apollo"));
            source = source.replace("https://github.com/dcatcher9/Apollo-3D", "");
            Matcher matcher = RETIRED_VISIBLE_NAME.matcher(source);
            while (matcher.find()) {
                offenders.add(directory.getName() + ": " + matcher.group());
            }
            if (source.contains("d\\'Moonlight 3D")
                    || source.contains("d'Moonlight 3D")
                    || source.contains("d\\'Sunshine 3D")
                    || source.contains("d'Sunshine 3D")) {
                offenders.add(directory.getName() + ": invalid French brand contraction");
            }

            Matcher nettest = NETTEST_SUCCESS.matcher(source);
            if (nettest.find()) {
                int brandedOccurrences = 0;
                int offset = 0;
                while ((offset = nettest.group(1).indexOf("Moonlight 3D", offset)) >= 0) {
                    brandedOccurrences++;
                    offset += "Moonlight 3D".length();
                }
                if (brandedOccurrences > 1) {
                    offenders.add(directory.getName()
                            + ": renamed Moonlight Internet Hosting Tool");
                }
            }
        }

        assertTrue("Retired visible product names remain: " + offenders, offenders.isEmpty());
    }

    @Test
    public void launcherUsesTransparentMoonlightIconFamily() throws IOException {
        String english = read(new File("src/main/res/values/strings.xml"));

        assertTrue(english.contains(
                "<string name=\"xr_home_title\">Moonlight 3D</string>"));
        assertTrue("Sunshine 3D link must target the paired host repository",
                english.contains("https://github.com/dcatcher9/Apollo-3D"));
        assertTrue("Third-party tool name must remain canonical",
                english.contains("Moonlight Internet Hosting Tool"));
        assertFalse("Do not invent a renamed third-party hosting tool",
                english.contains("Moonlight 3D Internet Hosting Tool"));
        assertFalse("An adaptive-icon override can flatten transparent backgrounds to black",
                new File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").exists());

        for (String density : new String[] {
                "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"
        }) {
            File source = new File(
                    "src/main/res/mipmap-" + density + "/ic_launcher.png");
            Bitmap icon = BitmapFactory.decodeFile(source.getAbsolutePath());
            assertTrue("launcher icon is unreadable: " + source, icon != null);
            assertTrue("launcher corner must be transparent: " + source,
                    Color.alpha(icon.getPixel(0, 0)) == 0);

            boolean foundTransparent = false;
            boolean foundVisible = false;
            for (int y = 0; y < icon.getHeight(); y++) {
                for (int x = 0; x < icon.getWidth(); x++) {
                    int alpha = Color.alpha(icon.getPixel(x, y));
                    foundTransparent |= alpha == 0;
                    foundVisible |= alpha == 255;
                }
            }
            assertTrue("launcher icon has no transparent canvas: " + source,
                    foundTransparent);
            assertTrue("launcher icon has no visible mark: " + source,
                    foundVisible);
        }
    }

    private static String read(File file) throws IOException {
        assertTrue("source not found: " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

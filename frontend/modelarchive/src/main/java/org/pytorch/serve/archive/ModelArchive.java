package org.pytorch.serve.archive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelArchive {

    private static final Logger logger = LoggerFactory.getLogger(ModelArchive.class);

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Pattern URL_PATTERN =
            Pattern.compile("http(s)?://.*", Pattern.CASE_INSENSITIVE);

    private static final String MANIFEST_FILE = "MANIFEST.json";

    private Manifest manifest;
    private String url;
    private File modelDir;
    private boolean extracted;

    public ModelArchive(Manifest manifest, String url, File modelDir, boolean extracted) {
        this.manifest = manifest;
        this.url = url;
        this.modelDir = modelDir;
        this.extracted = extracted;
    }

    public static ModelArchive downloadModel(String modelStore, String url)
            throws ModelException, IOException {
        if (URL_PATTERN.matcher(url).matches()) {
            File modelDir = download(url);
            return load(url, modelDir, true);
        }

        if (url.contains("..")) {
            throw new ModelNotFoundException("Relative path is not allowed in url: " + url);
        }

        if (modelStore == null) {
            throw new ModelNotFoundException("Model store has not been configured.");
        }

        File modelLocation = new File(modelStore, url);
        if (!modelLocation.exists()) {
            throw new ModelNotFoundException("Model not found in model store: " + url);
        }
        if (modelLocation.isFile()) {
            try (InputStream is = new FileInputStream(modelLocation)) {
                File unzipDir = unzip(is, null);
                return load(url, unzipDir, true);
            }
        }
        throw new ModelNotFoundException("Model not found at: " + url);
    }

    private static File download(String path) throws ModelException, IOException {
        HttpURLConnection conn;
        try {
            URL url = new URL(path);
            conn = (HttpURLConnection) url.openConnection();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new DownloadModelException(
                        "Failed to download model from: "
                                + path
                                + ", code: "
                                + conn.getResponseCode());
            }
        } catch (MalformedURLException | RuntimeException e) {
            // URLConnection may throw raw RuntimeException if port is out of range.
            throw new ModelNotFoundException("Invalid model url: " + path, e);
        } catch (IOException e) {
            throw new DownloadModelException("Failed to download model from: " + path, e);
        }

        try {
            String eTag = conn.getHeaderField("ETag");
            File tmpDir = new File(System.getProperty("java.io.tmpdir"));
            File modelDir = new File(tmpDir, "models");
            FileUtils.forceMkdir(modelDir);
            if (eTag != null) {
                if (eTag.startsWith("\"") && eTag.endsWith("\"") && eTag.length() > 2) {
                    eTag = eTag.substring(1, eTag.length() - 1);
                }
                File dir = new File(modelDir, eTag);
                if (dir.exists()) {
                    logger.info("model folder already exists: {}", eTag);
                    return dir;
                }
            }

            return unzip(conn.getInputStream(), eTag);
        } catch (SocketTimeoutException e) {
            throw new DownloadModelException("Download model timeout: " + path, e);
        }
    }

    private static ModelArchive load(String url, File dir, boolean extracted)
            throws InvalidModelException, IOException {
        boolean failed = true;
        try {
            File manifestFile = new File(dir, "MAR-INF/" + MANIFEST_FILE);
            Manifest manifest = null;
            if (manifestFile.exists()) {
                manifest = readFile(manifestFile, Manifest.class);
            } else {

                manifest = new Manifest();
            }

            failed = false;
            return new ModelArchive(manifest, url, dir, extracted);
        } finally {
            if (extracted && failed) {
                FileUtils.deleteQuietly(dir);
            }
        }
    }

    private static <T> T readFile(File file, Class<T> type)
            throws InvalidModelException, IOException {
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type);
        } catch (JsonParseException e) {
            throw new InvalidModelException("Failed to parse signature.json.", e);
        }
    }

    public static File unzip(InputStream is, String eTag) throws IOException {
        File tmpDir = FileUtils.getTempDirectory();
        File modelDir = new File(tmpDir, "models");
        FileUtils.forceMkdir(modelDir);

        File tmp = File.createTempFile("model", ".download");
        FileUtils.forceDelete(tmp);
        FileUtils.forceMkdir(tmp);

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        ZipUtils.unzip(new DigestInputStream(is, md), tmp);
        if (eTag == null) {
            eTag = Hex.toHexString(md.digest());
        }
        File dir = new File(modelDir, eTag);
        if (dir.exists()) {
            FileUtils.deleteDirectory(tmp);
            logger.info("model folder already exists: {}", eTag);
            return dir;
        }

        FileUtils.moveDirectory(tmp, dir);

        return dir;
    }

    public void validate() throws InvalidModelException {
        Manifest.Model model = manifest.getModel();
        try {
            if (model == null) {
                throw new InvalidModelException("Missing Model entry in manifest file.");
            }

            if (model.getModelName() == null) {
                throw new InvalidModelException("Model name is not defined.");
            }

            if (manifest.getRuntime() == null) {
                throw new InvalidModelException("Runtime is not defined or invalid.");
            }

            if (manifest.getEngine() != null && manifest.getEngine().getEngineName() == null) {
                throw new InvalidModelException("engineName is required in <engine>.");
            }
        } catch (InvalidModelException e) {
            clean();
            throw e;
        }
    }

    public String getHandler() {
        return manifest.getModel().getHandler();
    }

    public Manifest getManifest() {
        return manifest;
    }

    public String getUrl() {
        return url;
    }

    public File getModelDir() {
        return modelDir;
    }

    public String getModelName() {
        return manifest.getModel().getModelName();
    }
    
    public String getModelVersion() {
        return manifest.getModel().getModelVersion();
    }

    public void clean() {
        if (url != null && extracted) {
            FileUtils.deleteQuietly(modelDir);
        }
    }
}

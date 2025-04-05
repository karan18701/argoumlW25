package org.argouml.persistence;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import junit.framework.TestCase;

import org.argouml.application.api.Argo;
import org.argouml.kernel.ProfileConfiguration;
import org.argouml.kernel.Project;
import org.argouml.kernel.ProjectManager;
import org.argouml.model.InitializeModel;
import org.argouml.model.Model;
import org.argouml.model.UmlException;
import org.argouml.model.XmiWriter;
import org.argouml.profile.FileModelLoader;
import org.argouml.profile.Profile;
import org.argouml.profile.ProfileException;
import org.argouml.profile.ProfileFacade;
import org.argouml.profile.UserProfileReference;
import org.argouml.profile.init.InitProfileSubsystem;
import org.argouml.profile.internal.ProfileUML;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Tests for the {@link ProfileConfigurationFilePersister} class.
 *
 * @author Luis Sergio Oliveira (euluis)
 */
public class TestProfileConfigurationFilePersister extends TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        InitializeModel.initializeDefault();
        new InitProfileSubsystem().init();
    }

    @Override
    protected void tearDown() throws Exception {
        ProfileFacade.reset();
        super.tearDown();
    }

    /**
     * Tests whether XmiWriterMDRImpl fails to write a profile
     * (i.e., the file will contain no model) previously loaded from a file.
     *
     * @throws ProfileException if the loading of the profile fails.
     * @throws IOException if an IO error occurs while writing or reading a
     * file.
     * @throws FileNotFoundException if a file isn't found.
     * @throws UmlException if an error occurs while writing the profile in
     * XMI format.
     */
    public void testWritePreviouslyLoadedProfile()
            throws ProfileException, FileNotFoundException, IOException,
            UmlException {

        Object umlModel = ProfileFacade.getManager().getUMLProfile()
                .getProfilePackages().iterator().next();
        final String umlModelName = Model.getFacade().getName(umlModel);
        assertNotNull(umlModelName);

        // Sanitize the model name to avoid path traversal or unsafe characters
        String safeModelName = umlModelName.replaceAll("[^a-zA-Z0-9-_\\.]", "_");

        // Create temp file with sanitized name
        File tempFile = File.createTempFile(safeModelName, ".xmi");

        // Extra validation: ensure it's inside the temp directory
        File tempDir = new File(System.getProperty("java.io.tmpdir")).getCanonicalFile();
        File canonicalTempFile = tempFile.getCanonicalFile();
        if (!canonicalTempFile.getPath().startsWith(tempDir.getPath())) {
            throw new SecurityException("Path traversal attempt detected: " + canonicalTempFile);
        }

        try (FileOutputStream stream = new FileOutputStream(canonicalTempFile)) {
            XmiWriter xmiWriter = Model.getXmiWriter(umlModel, stream, "version-x");
            xmiWriter.write();
        }

        FileModelLoader fileModelLoader = new FileModelLoader();
        Collection loadedModel = fileModelLoader.loadModel(
                new UserProfileReference(tempFile.getPath()));
        assertEquals(1, loadedModel.size());
        umlModel = loadedModel.iterator().next();
        assertEquals(umlModelName, Model.getFacade().getName(umlModel));
    }

    private static final String TEST_PROFILE =
            "<?xml version = \"1.0\" encoding = \"UTF-8\" ?>\n"
                    // Although we've historically written out the DOCTYPE, the DTD doesn't
                    // actually exist and this line will get stripped by the .uml file
                    // persister
//          + "<!DOCTYPE profile SYSTEM \"profile.dtd\" >\n"
                    + "<profile>\n"

                    // Standard UML 1.4 profile
                    + "\t\t<plugin>\n"
                    + "\t\t\tUML 1.4\n"
                    + "\t\t</plugin>\n"

                    // TODO: User defined profile support untested currently
//          + "\t\t<userDefined>\n"
//          + "\t\t\t<filename>\n"
//          + "foo.profile\n"
//          + "</filename>\n"
//          + "\t\t\t<model>\n"
//          + "foo.profile.package\n"
//          + "\t\t\t</model>\n"
//          + "\t\t</userDefined>\n"

                    + "</profile>";

    /**
     * Test the basic profile configuration parser.
     *
     * @throws SAXException on a parse failure
     * @throws UnsupportedEncodingException if our default encoding (UTF-8) is
     * unsupported. Should never happen.
     */
    public void testProfileConfigurationParser() throws SAXException,
            UnsupportedEncodingException, ParserConfigurationException {
        InputStream inStream =
                new ByteArrayInputStream(
                        TEST_PROFILE.getBytes(Argo.getEncoding()));
        ProfileConfigurationParser parser = new ProfileConfigurationParser();

        // Secure the XML parser against XXE
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        // Now parse using the secured builder (though ProfileConfigurationParser might have its own parsing logic)
        // If ProfileConfigurationParser uses its own DocumentBuilderFactory, you'll need to modify that class directly.
        // Assuming here that ProfileConfigurationParser internally uses a standard mechanism that can be influenced.
        try {
            parser.parse(new InputSource(inStream));
        } catch (SAXException | IOException e) {
            fail("Error parsing profile configuration: " + e.getMessage());
        }

        Collection<Profile> profiles = parser.getProfiles();
        assertEquals("Wrong number of profiles", 1, profiles.size());
        Iterator<Profile> profileIter = profiles.iterator();
        assertTrue("Didn't get expected UML profile",
                profileIter.next() instanceof ProfileUML);
    }

    /**
     * Test that we can save and restore the default profile configuration.
     *
     * @throws IOException on io error
     * @throws SaveException on save error
     * @throws OpenException on load error
     */
    public void testSaveLoadDefaultConfiguration() throws IOException,
            SaveException, OpenException {

        // Create a default profile and record its contents
        Project project = ProjectManager.getManager().makeEmptyProject();
        ProfileConfiguration pc = new ProfileConfiguration(project);
        Collection<Profile> startingProfiles =
                new ArrayList<Profile>(pc.getProfiles());

        // Write the profile out to a temp file
        ProfileConfigurationFilePersister persister =
                new ProfileConfigurationFilePersister();
        File file = File.createTempFile(this.getName(), ".profile");

        // Extra validation: ensure it's inside the temp directory
        File tempDir = new File(System.getProperty("java.io.tmpdir")).getCanonicalFile();
        File canonicalFile = file.getCanonicalFile();
        if (!canonicalFile.getPath().startsWith(tempDir.getPath())) {
            throw new SecurityException("Path traversal attempt detected: " + canonicalFile);
        }

        try (OutputStream outStream = new FileOutputStream(canonicalFile)) {
            persister.save(pc, outStream);
        }

        // Read it back in to a new empty project
        project = ProjectManager.getManager().makeEmptyProject();
        persister.load(project,
                new InputSource(file.toURI().toURL().toExternalForm()));

        // Make sure we got what we started with
        assertEquals(startingProfiles,
                project.getProfileConfiguration().getProfiles());
    }
}

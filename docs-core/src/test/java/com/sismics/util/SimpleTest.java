package com.sismics.util;

import com.sismics.BaseTest;
import freemarker.ext.beans.BeansWrapper;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.SimpleScalar;
import freemarker.template.TemplateModelException;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.GroupCriteria;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.GroupDto;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.listener.async.FileDeletedAsyncListener;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.UserGroup;
import com.sismics.docs.core.service.FileSizeService;
import com.sismics.docs.core.util.TransactionUtil;

import com.sismics.docs.core.util.PdfUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.css.Selector;
import com.sismics.util.mime.MimeType;
import com.sismics.util.mime.MimeTypeUtil;
import com.sismics.util.totp.GoogleAuthenticator;
import com.sismics.util.totp.GoogleAuthenticatorKey;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Assert;
import org.junit.Test;
import com.sismics.docs.core.util.format.PdfFormatHandler;

import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import com.sismics.util.mime.MimeType;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ResourceBundle;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import java.io.InputStream;
import java.nio.file.Files;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.Assert.*;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.core.util.authentication.InternalAuthenticationHandler;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.util.format.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.StandardCopyOption;


public class SimpleTest extends BaseTest {
    @Test
    public void testLocaleUtilGetLocale() {
        assertEquals(Locale.ENGLISH, LocaleUtil.getLocale(null));
        assertEquals(Locale.ENGLISH, LocaleUtil.getLocale(""));

        Locale fr = LocaleUtil.getLocale("fr");
        assertEquals("fr", fr.getLanguage());
        assertEquals("", fr.getCountry());
        assertEquals("", fr.getVariant());

        Locale frFr = LocaleUtil.getLocale("fr_FR");
        assertEquals("fr", frFr.getLanguage());
        assertEquals("FR", frFr.getCountry());
        assertEquals("", frFr.getVariant());

        Locale frFrPosix = LocaleUtil.getLocale("fr_FR_POSIX");
        assertEquals("fr", frFrPosix.getLanguage());
        assertEquals("FR", frFrPosix.getCountry());
        assertEquals("POSIX", frFrPosix.getVariant());
    }

    @Test
    public void testHtmlToPlainText() {
        Document listDoc = Jsoup.parse("<p>A</p><ul><li>B</li><li>C</li></ul>");
        String listPlain = new HtmlToPlainText().getPlainText(listDoc);
        assertTrue(listPlain.contains("A"));
        assertTrue(listPlain.contains("* B"));
        assertTrue(listPlain.contains("* C"));

        Document linkDoc = Jsoup.parse("<a href=\"/x\">Link</a>", "https://example.com");
        String linkPlain = new HtmlToPlainText().getPlainText(linkDoc);
        assertTrue(linkPlain.contains("Link"));
        assertTrue(linkPlain.contains("<https://example.com/x>"));
    }

    @Test
    public void testResourceBundleModelExec() throws Exception {
        ResourceBundle bundle = new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[][]{
                        {"k", "Hello {0}."}
                };
            }
        };

        BeansWrapper wrapper = (BeansWrapper) new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_23).build();
        ResourceBundleModel model = new ResourceBundleModel(bundle, wrapper);

        try {
            model.exec(new ArrayList<>());
            fail();
        } catch (TemplateModelException e) {
            assertTrue(e.getMessage().contains("No message key"));
        }

        List<Object> args = new ArrayList<>();
        args.add(new SimpleScalar("k"));
        args.add(new SimpleScalar("Bob"));
        Object result = model.exec(args);
        assertNotNull(result);
        assertTrue(result.toString().contains("Bob"));

        List<Object> missing = new ArrayList<>();
        missing.add(new SimpleScalar("missing.key"));
        try {
            model.exec(missing);
            fail();
        } catch (TemplateModelException e) {
            assertTrue(e.getMessage().contains("No such key"));
        }
    }

    @Test
    public void testResourceUtilList() throws Exception {
        List<String> all = ResourceUtil.list(SimpleTest.class, "/file");
        assertTrue(all.contains("document.txt"));

        List<String> filtered = ResourceUtil.list(SimpleTest.class, "/file", (dir, name) -> name.endsWith(".pdf"));
        assertTrue(filtered.contains("udhr.pdf"));
    }

    @Test
    public void testTransactionUtilAndUserDao() {
        String username = "simpletest_" + UUID.randomUUID();
        TransactionUtil.handle(() -> {
            try {
                UserDao userDao = new UserDao();

                User user = new User();
                user.setUsername(username);
                user.setPassword("12345678");
                user.setEmail("toto@docs.com");
                user.setRoleId("admin");
                user.setStorageQuota(100_000L);
                userDao.create(user, username);

                Assert.assertNotNull(userDao.authenticate(username, "12345678"));
                Assert.assertNull(userDao.authenticate(username, "wrong"));
                Assert.assertNull(userDao.authenticate("missing_" + username, "12345678"));

                User active = userDao.getActiveByUsername(username);
                Assert.assertNotNull(active);

                User update = new User();
                update.setId(active.getId());
                update.setEmail("new@docs.com");
                update.setStorageQuota(100_001L);
                update.setStorageCurrent(0L);
                update.setTotpKey(null);
                update.setDisableDate(new Date());
                userDao.update(update, username);

                Assert.assertNull(userDao.authenticate(username, "12345678"));

                userDao.delete(username, active.getId());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testBuildCss_original() {
        Selector selector = new Selector(".test")
                .rule("background-color", "yellow")
                .rule("font-family", "Comic Sans");
        Assert.assertNotNull(selector.toString());
    }

    @Test
    public void testIssue373() throws Exception {
        PdfFormatHandler formatHandler = new PdfFormatHandler();
        String content = formatHandler.extractContent("deu", Paths.get(getResource("issue373.pdf").toURI()));
        Assert.assertTrue(content.contains("Aufrechterhaltung"));
        Assert.assertTrue(content.contains("Außentemperatur"));
        Assert.assertTrue(content.contains("Grundumsatzmessungen"));
        Assert.assertTrue(content.contains("ermitteln"));
    }

    @Test
    public void testGoogleAuthenticator_original() {
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        GoogleAuthenticatorKey key = gAuth.createCredentials();
        Assert.assertNotNull(key.getVerificationCode());
        Assert.assertEquals(5, key.getScratchCodes().size());
        int validationCode = gAuth.calculateCode(key.getKey(), new Date().getTime() / 30000);
        Assert.assertTrue(gAuth.authorize(key.getKey(), validationCode));
    }

    @Test
    public void computeGravatar_original() {
        Assert.assertEquals("0bc83cb571cd1c50ba6f3e8a78ef1346", ImageUtil.computeGravatar("MyEmailAddress@example.com "));
    }

    @Test
    public void computeGravatarTest() {
        Assert.assertEquals("0bc83cb571cd1c50ba6f3e8a78ef1346", ImageUtil.computeGravatar("MyEmailAddress@example.com "));
    }

    @Test
    public void mimeTypeUtil_original() throws Exception {
        Path path = Paths.get(getResource(FILE_ODT).toURI());
        Assert.assertEquals(MimeType.OPEN_DOCUMENT_TEXT, MimeTypeUtil.guessMimeType(path, FILE_ODT));

        path = Paths.get(getResource(FILE_DOCX).toURI());
        Assert.assertEquals(MimeType.OFFICE_DOCUMENT, MimeTypeUtil.guessMimeType(path, FILE_ODT));

        path = Paths.get(getResource(FILE_PPTX).toURI());
        Assert.assertEquals(MimeType.OFFICE_PRESENTATION, MimeTypeUtil.guessMimeType(path, FILE_PPTX));

        path = Paths.get(getResource(FILE_XLSX).toURI());
        Assert.assertEquals(MimeType.OFFICE_SHEET, MimeTypeUtil.guessMimeType(path, FILE_XLSX));

        path = Paths.get(getResource(FILE_TXT).toURI());
        Assert.assertEquals(MimeType.TEXT_PLAIN, MimeTypeUtil.guessMimeType(path, FILE_TXT));

        path = Paths.get(getResource(FILE_CSV).toURI());
        Assert.assertEquals(MimeType.TEXT_CSV, MimeTypeUtil.guessMimeType(path, FILE_CSV));

        path = Paths.get(getResource(FILE_PDF).toURI());
        Assert.assertEquals(MimeType.APPLICATION_PDF, MimeTypeUtil.guessMimeType(path, FILE_PDF));

        path = Paths.get(getResource(FILE_JPG).toURI());
        Assert.assertEquals(MimeType.IMAGE_JPEG, MimeTypeUtil.guessMimeType(path, FILE_JPG));

        path = Paths.get(getResource(FILE_GIF).toURI());
        Assert.assertEquals(MimeType.IMAGE_GIF, MimeTypeUtil.guessMimeType(path, FILE_GIF));

        path = Paths.get(getResource(FILE_PNG).toURI());
        Assert.assertEquals(MimeType.IMAGE_PNG, MimeTypeUtil.guessMimeType(path, FILE_PNG));

        path = Paths.get(getResource(FILE_ZIP).toURI());
        Assert.assertEquals(MimeType.APPLICATION_ZIP, MimeTypeUtil.guessMimeType(path, FILE_ZIP));

        path = Paths.get(getResource(FILE_WEBM).toURI());
        Assert.assertEquals(MimeType.VIDEO_WEBM, MimeTypeUtil.guessMimeType(path, FILE_WEBM));

        path = Paths.get(getResource(FILE_MP4).toURI());
        Assert.assertEquals(MimeType.VIDEO_MP4, MimeTypeUtil.guessMimeType(path, FILE_MP4));
    }

    @Test
    public void listFiles_original() throws Exception {
        List<String> fileList = ResourceUtil.list(Test.class, "/junit/framework");
        Assert.assertTrue(fileList.contains("Test.class"));

        fileList = ResourceUtil.list(Test.class, "/junit/framework/");
        Assert.assertTrue(fileList.contains("Test.class"));

        fileList = ResourceUtil.list(Test.class, "junit/framework/");
        Assert.assertTrue(fileList.contains("Test.class"));

        fileList = ResourceUtil.list(Test.class, "junit/framework/");
        Assert.assertTrue(fileList.contains("Test.class"));
    }

    
    @Before
    public void setUp() {
        // Initialize the entity manager
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
    }

    @After
    public void tearDown() {
        ThreadLocalContext.get().getEntityManager().getTransaction().rollback();
    }

    protected User createUser(String userName) throws Exception {
        UserDao userDao = new UserDao();
        User user = new User();
        user.setUsername(userName);
        user.setPassword("12345678");
        user.setEmail("toto@docs.com");
        user.setRoleId("admin");
        user.setStorageQuota(100_000L);
        userDao.create(user, userName);
        return user;
    }

    protected File createFile(User user, long fileSize) throws Exception {
        FileDao fileDao = new FileDao();
        try(InputStream inputStream = getSystemResourceAsStream(FILE_JPG)) {
            File file = new File();
            file.setId("apollo_portrait");
            file.setUserId(user.getId());
            file.setVersion(0);
            file.setMimeType(MimeType.IMAGE_JPEG);
            file.setSize(fileSize);
            String fileId = fileDao.create(file, user.getId());
            Cipher cipher = EncryptionUtil.getEncryptionCipher(user.getPrivateKey());
            Files.copy(new CipherInputStream(inputStream, cipher), DirectoryUtil.getStorageDirectory().resolve(fileId), REPLACE_EXISTING);
            return file;
        }
    }

    @Test
    public void generatePrivateKeyTest() {
        String key = EncryptionUtil.generatePrivateKey();
        System.out.println(key);
        Assert.assertFalse(Strings.isNullOrEmpty(key));
    }
    
    @Test
    public void encryptStreamTest() throws Exception {
        try {
            EncryptionUtil.getEncryptionCipher("");
            Assert.fail();
        } catch (IllegalArgumentException e) {
            // NOP
        }
        Cipher cipher = EncryptionUtil.getEncryptionCipher("OnceUponATime");
        InputStream inputStream = new CipherInputStream(getSystemResourceAsStream(FILE_PDF), cipher);
        byte[] encryptedData = ByteStreams.toByteArray(inputStream);
        byte[] assertData = ByteStreams.toByteArray(getSystemResourceAsStream(FILE_PDF_ENCRYPTED));

        Assert.assertEquals(encryptedData.length, assertData.length);
    }
    
    @Test
    public void decryptStreamTest() throws Exception {
        InputStream inputStream = EncryptionUtil.decryptInputStream(
                getSystemResourceAsStream(FILE_PDF_ENCRYPTED), "OnceUponATime");
        byte[] encryptedData = ByteStreams.toByteArray(inputStream);
        byte[] assertData = ByteStreams.toByteArray(getSystemResourceAsStream(FILE_PDF));
        
        Assert.assertEquals(encryptedData.length, assertData.length);
    }

    @Test
    public void extractContentOpenDocumentTextTest() throws Exception {
        Path path = Paths.get(getResource(FILE_ODT).toURI());
        FormatHandler formatHandler = FormatHandlerUtil.find(MimeTypeUtil.guessMimeType(path, FILE_ODT));
        Assert.assertNotNull(formatHandler);
        Assert.assertTrue(formatHandler instanceof OdtFormatHandler);
        String content = formatHandler.extractContent("eng", path);
        Assert.assertTrue(content.contains("Lorem ipsum dolor sit amen."));
    }
    
    @Test
    public void extractContentOfficeDocumentTest() throws Exception {
        Path path = Paths.get(getResource(FILE_DOCX).toURI());
        FormatHandler formatHandler = FormatHandlerUtil.find(MimeTypeUtil.guessMimeType(path, FILE_DOCX));
        Assert.assertNotNull(formatHandler);
        Assert.assertTrue(formatHandler instanceof DocxFormatHandler);
        String content = formatHandler.extractContent("eng", path);
        Assert.assertTrue(content.contains("Lorem ipsum dolor sit amen."));
    }

    @Test
    public void extractContentPowerpointTest() throws Exception {
        Path path = Paths.get(getResource(FILE_PPTX).toURI());
        FormatHandler formatHandler = FormatHandlerUtil.find(MimeTypeUtil.guessMimeType(path, FILE_PPTX));
        Assert.assertNotNull(formatHandler);
        Assert.assertTrue(formatHandler instanceof PptxFormatHandler);
        String content = formatHandler.extractContent("eng", path);
        Assert.assertTrue(content.contains("Scaling"));
    }

    @Test
    public void extractContentPdf() throws Exception {
        Path path = Paths.get(getResource(FILE_PDF).toURI());
        FormatHandler formatHandler = FormatHandlerUtil.find(MimeTypeUtil.guessMimeType(path, FILE_PDF));
        Assert.assertNotNull(formatHandler);
        Assert.assertTrue(formatHandler instanceof PdfFormatHandler);
        String content = formatHandler.extractContent("eng", path);
        Assert.assertTrue(content.contains("All human beings are born free and equal in dignity and rights."));
    }

    @Test
    public void extractContentScannedPdf() throws Exception {
        Path path = Paths.get(getResource("scanned.pdf").toURI());
        FormatHandler formatHandler = FormatHandlerUtil.find(MimeTypeUtil.guessMimeType(path, FILE_PDF_SCANNED));
        Assert.assertNotNull(formatHandler);
        Assert.assertTrue(formatHandler instanceof PdfFormatHandler);
        String content = formatHandler.extractContent("eng", path);
        Assert.assertTrue(content.contains("All human beings are born free and equal in dignity and rights."));
    }

    @Test
    public void convertToPdfTest() throws Exception {
        try (InputStream inputStream0 = getSystemResourceAsStream(FILE_JPG2);
                InputStream inputStream1 = getSystemResourceAsStream(FILE_JPG);
                InputStream inputStream2 = getSystemResourceAsStream(FILE_PDF_ENCRYPTED);
                InputStream inputStream3 = getSystemResourceAsStream(FILE_DOCX);
                InputStream inputStream4 = getSystemResourceAsStream(FILE_ODT);
                InputStream inputStream5 = getSystemResourceAsStream(FILE_PPTX)) {
            // Document
            DocumentDto documentDto = new DocumentDto();
            documentDto.setTitle("My super document 1");
            documentDto.setDescription("Lorem ipsum dolor sit amet, consectetur adipiscing elit.\r\n Duis id turpis iaculis, commodo est ac, efficitur quam.\t Nam accumsan magna in orci vulputate ultricies. Sed vulputate neque magna, at laoreet leo ultricies vel. Proin eu hendrerit felis. Quisque sit amet arcu efficitur, pulvinar orci sed, imperdiet elit. Nunc posuere ex sed fermentum congue. Aliquam ultrices convallis finibus. Praesent iaculis justo vitae dictum auctor. Praesent suscipit imperdiet erat ac maximus. Aenean pharetra quam sed fermentum commodo. Donec sagittis ipsum nibh, id congue dolor venenatis quis. In tincidunt nisl non ex sollicitudin, a imperdiet neque scelerisque. Nullam lacinia ac orci sed faucibus. Donec tincidunt venenatis justo, nec fermentum justo rutrum a.");
            documentDto.setSubject("A set of random picture");
            documentDto.setIdentifier("ID-2016-08-00001");
            documentDto.setPublisher("My Publisher, Inc.");
            documentDto.setFormat("A4 standard ISO format");
            documentDto.setType("Image");
            documentDto.setCoverage("France");
            documentDto.setRights("Public Domain");
            documentDto.setLanguage("en");
            documentDto.setCreator("user1");
            documentDto.setCreateTimestamp(new Date().getTime());
            
            // First file
            Files.copy(inputStream0, DirectoryUtil.getStorageDirectory().resolve("apollo_landscape"), StandardCopyOption.REPLACE_EXISTING);
            File file0 = new File();
            file0.setId("apollo_landscape");
            file0.setMimeType(MimeType.IMAGE_JPEG);
            
            // Second file
            Files.copy(inputStream1, DirectoryUtil.getStorageDirectory().resolve("apollo_portrait"), StandardCopyOption.REPLACE_EXISTING);
            File file1 = new File();
            file1.setId("apollo_portrait");
            file1.setMimeType(MimeType.IMAGE_JPEG);
            
            // Third file
            Files.copy(inputStream2, DirectoryUtil.getStorageDirectory().resolve("udhr"), StandardCopyOption.REPLACE_EXISTING);
            File file2 = new File();
            file2.setId("udhr");
            file2.setPrivateKey("OnceUponATime");
            file2.setMimeType(MimeType.APPLICATION_PDF);
            
            // Fourth file
            Files.copy(inputStream3, DirectoryUtil.getStorageDirectory().resolve("document_docx"), StandardCopyOption.REPLACE_EXISTING);
            File file3 = new File();
            file3.setId("document_docx");
            file3.setMimeType(MimeType.OFFICE_DOCUMENT);
            
            // Fifth file
            Files.copy(inputStream4, DirectoryUtil.getStorageDirectory().resolve("document_odt"), StandardCopyOption.REPLACE_EXISTING);
            File file4 = new File();
            file4.setId("document_odt");
            file4.setMimeType(MimeType.OPEN_DOCUMENT_TEXT);

            // Sixth file
            Files.copy(inputStream5, DirectoryUtil.getStorageDirectory().resolve("document_pptx"), StandardCopyOption.REPLACE_EXISTING);
            File file5 = new File();
            file5.setId("document_pptx");
            file5.setMimeType(MimeType.OFFICE_PRESENTATION);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PdfUtil.convertToPdf(documentDto, Lists.newArrayList(file0, file1, file2, file3, file4, file5), true, true, 10, outputStream);
            Assert.assertTrue(outputStream.toByteArray().length > 0);
        }
    }

    @Test
    public void processFileTest() throws Exception {
        User user = createUser("processFileTest");

        FileDao fileDao = new FileDao();
        File file = createFile(user, File.UNKNOWN_SIZE);
        var fileSizeService = new FileSizeService() {
            public void runOnceForTest() {
                super.runOneIteration();
            }
        };
        fileSizeService.runOnceForTest();
        Assert.assertEquals(fileDao.getFile(file.getId()).getSize(), Long.valueOf(FILE_JPG_SIZE));
    }

    @Test
    public void updateQuotaSizeKnown() throws Exception {
        User user = createUser("updateQuotaSizeKnown");
        File file = createFile(user, FILE_JPG_SIZE);
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        FileDeletedAsyncListener fileDeletedAsyncListener = new FileDeletedAsyncListener();
        TransactionUtil.commit();
        FileDeletedAsyncEvent event = new FileDeletedAsyncEvent();
        event.setFileSize(FILE_JPG_SIZE);
        event.setFileId(file.getId());
        event.setUserId(user.getId());
        fileDeletedAsyncListener.on(event);
        Assert.assertEquals(userDao.getById(user.getId()).getStorageCurrent(), Long.valueOf(10_000 - FILE_JPG_SIZE));
    }

    @Test
    public void updateQuotaSizeUnknown() throws Exception {
        User user = createUser("updateQuotaSizeUnknown");
        File file = createFile(user, File.UNKNOWN_SIZE);
        UserDao userDao = new UserDao();
        user = userDao.getById(user.getId());
        user.setStorageCurrent(10_000L);
        userDao.updateQuota(user);

        FileDeletedAsyncListener fileDeletedAsyncListener = new FileDeletedAsyncListener();
        TransactionUtil.commit();
        FileDeletedAsyncEvent event = new FileDeletedAsyncEvent();
        event.setFileSize(FILE_JPG_SIZE);
        event.setFileId(file.getId());
        event.setUserId(user.getId());
        fileDeletedAsyncListener.on(event);
        Assert.assertEquals(userDao.getById(user.getId()).getStorageCurrent(), Long.valueOf(10_000 - FILE_JPG_SIZE));
    }

    @Test
    public void testJpa() throws Exception {
        // Create a user
        UserDao userDao = new UserDao();
        User user = createUser("testJpa");

        TransactionUtil.commit();

        // Search a user by his ID
        user = userDao.getById(user.getId());
        Assert.assertNotNull(user);
        Assert.assertEquals("toto@docs.com", user.getEmail());

        // Authenticate using the database
        Assert.assertNotNull(new InternalAuthenticationHandler().authenticate("testJpa", "12345678"));

        // Delete the created user
        userDao.delete("testJpa", user.getId());
        TransactionUtil.commit();
    }

    @Test
    public void tagDaoCrudAndSearchTest() {
        TagDao tagDao = new TagDao();
        User user;
        try {
            user = createUser("tagDaoCrudAndSearchTest");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Tag tag = new Tag();
        tag.setName("alpha");
        tag.setColor("#ffffff");
        tag.setUserId(user.getId());
        String tagId = tagDao.create(tag, user.getId());

        Tag tagDb = tagDao.getById(tagId);
        Assert.assertNotNull(tagDb);
        Assert.assertEquals("alpha", tagDb.getName());
        Assert.assertEquals("#ffffff", tagDb.getColor());
        Assert.assertEquals(user.getId(), tagDb.getUserId());

        List<TagDto> before = tagDao.findByCriteria(new TagCriteria().setId(tagId), new SortCriteria(0, true));
        Assert.assertEquals(1, before.size());
        Assert.assertEquals(tagId, before.get(0).getId());
        Assert.assertEquals("alpha", before.get(0).getName());

        Tag updated = new Tag();
        updated.setId(tagId);
        updated.setName("beta");
        updated.setColor("#000000");
        updated.setParentId(null);
        tagDao.update(updated, user.getId());

        List<TagDto> after = tagDao.findByCriteria(new TagCriteria().setId(tagId), new SortCriteria(0, true));
        Assert.assertEquals(1, after.size());
        Assert.assertEquals("beta", after.get(0).getName());
        Assert.assertEquals("#000000", after.get(0).getColor());

        tagDao.delete(tagId, user.getId());

        List<TagDto> deleted = tagDao.findByCriteria(new TagCriteria().setId(tagId), new SortCriteria(0, true));
        Assert.assertTrue(deleted.isEmpty());
    }

    @Test
    public void groupDaoMemberLifecycleTest() {
        GroupDao groupDao = new GroupDao();
        User user;
        try {
            user = createUser("groupDaoMemberLifecycleTest");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Group group = new Group().setName("alpha_group");
        String groupId = groupDao.create(group, user.getId());

        Assert.assertNotNull(groupDao.getActiveById(groupId));
        Assert.assertNotNull(groupDao.getActiveByName("alpha_group"));

        UserGroup userGroup = new UserGroup();
        userGroup.setUserId(user.getId());
        userGroup.setGroupId(groupId);
        groupDao.addMember(userGroup);

        List<GroupDto> userGroups = groupDao.findByCriteria(new GroupCriteria().setUserId(user.getId()).setRecursive(false), new SortCriteria(0, true));
        Assert.assertEquals(1, userGroups.size());
        Assert.assertEquals(groupId, userGroups.get(0).getId());

        groupDao.removeMember(groupId, user.getId());

        List<GroupDto> afterRemove = groupDao.findByCriteria(new GroupCriteria().setUserId(user.getId()).setRecursive(false), new SortCriteria(0, true));
        Assert.assertTrue(afterRemove.isEmpty());

        groupDao.delete(groupId, user.getId());
        Assert.assertNull(groupDao.getActiveById(groupId));
    }
}

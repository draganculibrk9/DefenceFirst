package timejts.PKI.services;

import org.apache.commons.io.FileUtils;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.X509KeyUsage;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import timejts.PKI.dto.CertAuthorityDTO;
import timejts.PKI.exceptions.CANotValidException;
import timejts.PKI.exceptions.ValidCertificateAlreadyExists;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Vector;

import static timejts.PKI.utils.Utilities.*;

@Service
public class CertificateService {

    private static String csrFolder = "src/main/resources/static/csr/";
    private static String caKeystore = "src/main/resources/static/keystore/ca.jks";
    private static String nonCAKeystore = "src/main/resources/static/keystore/nonCA.jks";

    @Value("${server.ssl.key-store}")
    private String keystorePath;

    @Value("${server.ssl.key-store-password}")
    private String keystorePassword;

    @Value("${server.ssl.key-password}")
    private String keyPassword;

    @Value("${server.ssl.key-alias}")
    private String root;

    public Object createNonCACertificate(String commonName, String caName) throws KeyStoreException, IOException, CertificateException, CANotValidException, UnrecoverableKeyException, NoSuchAlgorithmException, OperatorCreationException, ClassNotFoundException, InvalidKeyException {
        // Load non CA keystore
        KeyStore nonCAKS = KeyStore.getInstance(KeyStore.getDefaultType());
        String nonCAPass = "nonCA" + keystorePassword;
        nonCAKS.load(new FileInputStream(nonCAKeystore), nonCAPass.toCharArray());

        // Check if subject already has valid certificate
        X509Certificate subjCert = (X509Certificate) nonCAKS.getCertificate(commonName);
        if (subjCert != null) {
            try {
                subjCert.checkValidity();
                throw new ValidCertificateAlreadyExists("Subject already has valid certificate");
            } catch (Exception ignored) {
            }
        }

        // Load CA keystore
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        String keyCA = "ca" + keystorePassword;
        ks.load(new FileInputStream(caKeystore), keyCA.toCharArray());

        // Get CA certificate and private key and check validity of CA certificate
        JcaContentSignerBuilder builder = new JcaContentSignerBuilder("SHA256WithRSAEncryption");
        builder = builder.setProvider("BC");
        X509Certificate cert = (X509Certificate) ks.getCertificate(caName);
        cert.checkValidity();
        checkCAEndDate(cert.getNotAfter());
        String caKeyPass = keyPassword + "-" + commonName;
        PrivateKey privKey = (PrivateKey) ks.getKey(commonName, caKeyPass.toCharArray());
        ContentSigner contentSigner = builder.build(privKey);
        X500Name issuerName = new JcaX509CertificateHolder(cert).getSubject();

        // Load certificate signing request
        String fileName = csrFolder + commonName + ".csr";
        File csrFile = new File(fileName);
        byte[] csrData = FileUtils.readFileToByteArray(csrFile);
        JcaPKCS10CertificationRequest csr = new JcaPKCS10CertificationRequest(csrData);

        // Generate serial number and set start/end date
        BigInteger serialNumber = new BigInteger(64, new SecureRandom());
        Date startDate = new Date();
        LocalDateTime endLocalDate = LocalDateTime.from(cert.getNotAfter().toInstant()).minusMonths(3);
        Date endDate = Date.from(endLocalDate.atZone(ZoneId.systemDefault()).toInstant());

        // Set certificate extensions and generate certificate
        X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(issuerName, serialNumber, startDate, endDate, csr
                .getSubject(), csr.getPublicKey());
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.19"), true, new BasicConstraints(false));
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.15"), true,
                new X509KeyUsage(X509KeyUsage.digitalSignature | X509KeyUsage.keyEncipherment));
        Vector<KeyPurposeId> extendedKeyUsages = new Vector<>();
        extendedKeyUsages.add(KeyPurposeId.id_kp_serverAuth);
        extendedKeyUsages.add(KeyPurposeId.id_kp_clientAuth);
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.37"), false, new ExtendedKeyUsage(extendedKeyUsages));
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.35"), false, new AuthorityKeyIdentifier((SubjectPublicKeyInfo) cert
                .getPublicKey()));
        X509CertificateHolder certHolder = certGen.build(contentSigner);

        // Convert to X509 certificate
        JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter();
        certConverter = certConverter.setProvider("BC");
        X509Certificate newCertificate = certConverter.getCertificate(certHolder);

        // Save keystore and serial number
        ks.setCertificateEntry(commonName, newCertificate);
        saveKeyStore(ks, nonCAKeystore, nonCAPass);
        saveSerialNumber(serialNumber);

        // Delete Certificate signing request
        csrFile.delete();

        // Create certificate file
        File certificateFile = x509CertificateToPem(cert, commonName);

        // Send certificate on email address

        return null;
    }

    public Object createCACertificate(CertAuthorityDTO certAuth) throws KeyStoreException, IOException, UnrecoverableKeyException, NoSuchAlgorithmException, OperatorCreationException, CertificateException, ClassNotFoundException {
        // Get data about CA
        X500Name subjectCA = generateX500Name(certAuth);

        // Load keystore
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(new FileInputStream(keystorePath), keystorePassword.toCharArray());

        // Get root certificate and private key
        JcaContentSignerBuilder builder = new JcaContentSignerBuilder("SHA256WithRSAEncryption");
        builder = builder.setProvider("BC");
        Certificate cert = ks.getCertificate(root);
        PrivateKey privKey = (PrivateKey) ks.getKey(root, keyPassword.toCharArray());
        ContentSigner contentSigner = builder.build(privKey);
        X500Name issuerName = new JcaX509CertificateHolder((X509Certificate) cert).getSubject();

        // Generate CA public and private key
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        KeyPair kp = kpg.generateKeyPair();

        // Generate serial number and set start/end date
        BigInteger serialNumber = new BigInteger(64, new SecureRandom());
        Date dt = new Date();
        LocalDateTime endLocalDate = LocalDateTime.from(dt.toInstant()).plusYears(3);
        Date endDate = Date.from(endLocalDate.atZone(ZoneId.systemDefault()).toInstant());

        // Set certificate extensions and generate certificate
        X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(issuerName, serialNumber, dt, endDate, subjectCA, kp
                .getPublic());
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.19"), true, new BasicConstraints(true));
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.15"), true,
                new X509KeyUsage(X509KeyUsage.keyCertSign | X509KeyUsage.digitalSignature | X509KeyUsage.keyEncipherment));
        Vector<KeyPurposeId> extendedKeyUsages = new Vector<>();
        extendedKeyUsages.add(KeyPurposeId.id_kp_serverAuth);
        extendedKeyUsages.add(KeyPurposeId.id_kp_clientAuth);
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.37"), false, new ExtendedKeyUsage(extendedKeyUsages));
        certGen.addExtension(new ASN1ObjectIdentifier("2.5.29.35"), false, new AuthorityKeyIdentifier((SubjectPublicKeyInfo) cert
                .getPublicKey()));
        X509CertificateHolder certHolder = certGen.build(contentSigner);

        // Convert to X509 certificate
        JcaX509CertificateConverter certConverter = new JcaX509CertificateConverter();
        certConverter = certConverter.setProvider("BC");
        X509Certificate newCertificate = certConverter.getCertificate(certHolder);

        // Save certificate and private key in keystore and save serial number
        KeyStore caKS = KeyStore.getInstance(KeyStore.getDefaultType());
        String caPass = "ca" + keystorePassword;
        caKS.load(new FileInputStream(caKeystore), caPass.toCharArray());
        KeyStore.PrivateKeyEntry privKeyEntry = new KeyStore.PrivateKeyEntry(kp.getPrivate(),
                new Certificate[]{newCertificate});
        String pass = keyPassword + "-" + certAuth.getCommonName();
        caKS.setEntry(certAuth.getCommonName(), privKeyEntry, new KeyStore.PasswordProtection(pass.toCharArray()));
        saveKeyStore(caKS, caKeystore, caPass);
        saveSerialNumber(serialNumber);

        return "CA Certificate " + certAuth.getCommonName() + " successfully created";
    }

    public String submitCSR(byte[] csrData) throws IOException {
        JcaPKCS10CertificationRequest csr = new JcaPKCS10CertificationRequest(csrData);
        X500Name subjectName = csr.getSubject();
        RDN[] rdns = subjectName.getRDNs(BCStyle.CN);
        String commonName = IETFUtils.valueToString(rdns[0].getFirst().getValue());
        String fileName = csrFolder + commonName + ".csr";
        FileUtils.writeByteArrayToFile(new File(fileName), csrData);

        return "Certificate signing request successfully submitted";
    }
}
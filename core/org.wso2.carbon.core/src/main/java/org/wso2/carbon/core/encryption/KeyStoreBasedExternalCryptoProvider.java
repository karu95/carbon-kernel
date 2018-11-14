/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.core.encryption;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.crypto.api.CertificateInfo;
import org.wso2.carbon.crypto.api.CryptoContext;
import org.wso2.carbon.crypto.api.CryptoException;
import org.wso2.carbon.crypto.api.ExternalCryptoProvider;
import org.wso2.carbon.crypto.api.HybridEncryptionInput;
import org.wso2.carbon.crypto.api.HybridEncryptionOutput;
import org.wso2.carbon.crypto.api.PrivateKeyInfo;

import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.wso2.carbon.base.MultitenantConstants.SUPER_TENANT_ID;

/**
 * The Carbon KeyStoreManager based implementation of {@link ExternalCryptoProvider}
 */
public class KeyStoreBasedExternalCryptoProvider implements ExternalCryptoProvider {

    private static Log log = LogFactory.getLog(KeyStoreBasedExternalCryptoProvider.class);
    private static SecureRandom random = new SecureRandom();

    /**
     * Computes and returns the signature of given data, using the underlying key store.
     *
     * @param data                    The data whose signature is calculated.
     * @param algorithm               The signature + hashing algorithm to be used in signing.
     * @param javaSecurityAPIProvider The Java Security API provider.
     * @param cryptoContext           The context information needed for signing.
     * @param privateKeyInfo          Information about the private key.
     * @return The signature
     * @throws CryptoException If something unexpected happens during the signing operation.
     */
    @Override
    public byte[] sign(byte[] data, String algorithm, String javaSecurityAPIProvider, CryptoContext cryptoContext,
                       PrivateKeyInfo privateKeyInfo)
            throws CryptoException {

        try {
            Signature signature;
            if (StringUtils.isBlank(javaSecurityAPIProvider)) {
                signature = Signature.getInstance(algorithm);
            } else {
                signature = Signature.getInstance(algorithm, javaSecurityAPIProvider);
            }

            PrivateKey privateKey = getPrivateKey(cryptoContext, privateKeyInfo);

            if (privateKey == null) {
                String errorMessage = String.format("Could not retrieve the private key using '%s' and '%s'. ",
                        privateKeyInfo, cryptoContext);
                log.error(errorMessage);
                throw new CryptoException(errorMessage);
            }

            signature.initSign(privateKey);
            signature.update(data);

            byte[] signatureBytes = signature.sign();

            if (log.isDebugEnabled()) {
                log.debug(String.format("Successfully signed data using the algorithm '%s' and the " +
                                "Java Security API provider '%s'; %s ; %s", algorithm, javaSecurityAPIProvider,
                        cryptoContext, privateKeyInfo));
            }

            return signatureBytes;
        } catch (Exception e) {
            String errorMessage = String.format("An error occurred while signing using the algorithm : '%s' and " +
                            "the Java Security API provider : '%s'; %s ; %s", algorithm, javaSecurityAPIProvider,
                    cryptoContext, privateKeyInfo);

            // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new CryptoException(errorMessage, e);
        }
    }

    /**
     * Computes and returns the cleartext of the given ciphertext.
     *
     * @param ciphertext              The ciphertext to be decrypted.
     * @param algorithm               The encryption / decryption algorithm
     * @param javaSecurityAPIProvider The Java Security API provider.
     * @param cryptoContext           The context information needed for signing.
     * @param privateKeyInfo          Information about the private key.
     * @return The cleartext
     * @throws CryptoException If something unexpected happens during the decryption operation.
     */
    @Override
    public byte[] decrypt(byte[] ciphertext, String algorithm, String javaSecurityAPIProvider,
                          CryptoContext cryptoContext, PrivateKeyInfo privateKeyInfo) throws CryptoException {

        try {
            Cipher cipher;

            if (StringUtils.isBlank(javaSecurityAPIProvider)) {
                cipher = Cipher.getInstance(algorithm);
            } else {
                cipher = Cipher.getInstance(algorithm, javaSecurityAPIProvider);
            }

            PrivateKey privateKey = getPrivateKey(cryptoContext, privateKeyInfo);

            if (privateKey == null) {
                String errorMessage = String.format("Could not retrieve the private key using '%s' and '%s'. ",
                        privateKeyInfo, cryptoContext);
                log.error(errorMessage);
                throw new CryptoException(errorMessage);
            }

            cipher.init(Cipher.DECRYPT_MODE, privateKey);

            byte[] cleartext = cipher.doFinal(ciphertext);

            if (log.isDebugEnabled()) {
                log.debug(String.format("Successfully decrypted data using the algorithm '%s' and the " +
                                "Java Security API provider '%s'; %s ; %s", algorithm, javaSecurityAPIProvider,
                        cryptoContext, privateKeyInfo));
            }

            return cleartext;
        } catch (Exception e) {
            String errorMessage = String.format("An error occurred while decrypting using the algorithm : '%s' and " +
                            "the Java Security API provider : '%s'; %s ; %s", algorithm, javaSecurityAPIProvider,
                    cryptoContext, privateKeyInfo);
            // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new CryptoException(errorMessage, e);
        }
    }

    /**
     * Computes and returns the ciphertext of the given cleartext.
     * When needed the public key is retrieved using Carbon KeyStoreManager.
     *
     * @param plaintext               The cleartext to be encrypted.
     * @param algorithm               The signature + hashing algorithm to be used in signing.
     * @param javaSecurityAPIProvider The Java Security API provider.
     * @param cryptoContext           The context information which was used to find discovery information about the
     *                                certificate
     *                                of the external entity.
     * @param certificateInfo         The information which is needed to retrieve the certificate.
     *                                If this information is not sufficient the {@link CryptoContext} will be used to
     *                                get more information.
     * @return The cleartext
     * @throws CryptoException If something unexpected happens during the encryption operation.
     */
    @Override
    public byte[] encrypt(byte[] plaintext, String algorithm, String javaSecurityAPIProvider,
                          CryptoContext cryptoContext, CertificateInfo certificateInfo) throws CryptoException {

        try {
            Cipher cipher;

            if (StringUtils.isBlank(javaSecurityAPIProvider)) {
                cipher = Cipher.getInstance(algorithm);
            } else {
                cipher = Cipher.getInstance(algorithm, javaSecurityAPIProvider);
            }

            Certificate certificate = getCertificate(cryptoContext, certificateInfo);

            if (certificate == null) {
                String errorMessage = String.format("Could not retrieve the certificate using '%s' and '%s'. ",
                        certificateInfo, cryptoContext);
                log.error(errorMessage);
                throw new CryptoException(errorMessage);
            }

            cipher.init(Cipher.ENCRYPT_MODE, certificate);
            byte[] ciphertext = cipher.doFinal(plaintext);

            if (log.isDebugEnabled()) {
                log.debug(String.format("Successfully encrypted data using the algorithm '%s' and the " +
                                "Java Security API provider '%s'; %s ; %s", algorithm, javaSecurityAPIProvider,
                        cryptoContext, certificateInfo));
            }

            return ciphertext;
        } catch (Exception e) {
            String errorMessage = String.format("An error occurred while encrypting using the algorithm '%s' and the " +
                            "Java Security API provider '%s'; %s ; %s", algorithm, javaSecurityAPIProvider,
                    cryptoContext, certificateInfo);
            // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new CryptoException(errorMessage, e);
        }

    }

    /**
     * Verifies whether given signature of the given data was generated by a trusted external party.
     * When needed the public key is retrieved using Carbon KeyStoreManager.
     *
     * @param data                    The data which was the signature generated on.
     * @param signatureBytes          The signature bytes of data.
     * @param algorithm               The signature + hashing algorithm to be used in signing.
     * @param javaSecurityAPIProvider The Java Security API provider.
     * @param cryptoContext           The context information which is needed to discover the public key of
     *                                the external entity.
     * @param certificateInfo         The information which is needed to retrieve the certificate.
     *                                If this information is not sufficient the {@link CryptoContext} will be used to
     *                                get more information.
     * @return true if signature can be verified, false otherwise.
     * @throws CryptoException If something unexpected happens during the signature verification.
     */
    @Override
    public boolean verifySignature(byte[] data, byte[] signatureBytes, String algorithm, String javaSecurityAPIProvider,
                                   CryptoContext cryptoContext, CertificateInfo certificateInfo)
            throws CryptoException {

        try {
            Signature signature;

            if (StringUtils.isBlank(javaSecurityAPIProvider)) {
                signature = Signature.getInstance(algorithm);
            } else {
                signature = Signature.getInstance(algorithm, javaSecurityAPIProvider);
            }

            Certificate certificate = getCertificate(cryptoContext, certificateInfo);

            if (certificate == null) {
                String errorMessage = String.format("Could not retrieve the certificate using '%s' and '%s'. ",
                        certificateInfo, cryptoContext);
                log.error(errorMessage);
                throw new CryptoException(errorMessage);
            }

            signature.initVerify(certificate);
            signature.update(data);

            boolean verificationResult = signature.verify(signatureBytes);

            if (log.isDebugEnabled()) {
                log.debug(String.format("Successfully carried out the signature validation operation using the " +
                                "algorithm '%s' and the Java Security API provider '%s'; %s ; %s. " +
                                "Verification Result : '%s'",
                        algorithm, javaSecurityAPIProvider, cryptoContext, certificateInfo, verificationResult));
            }

            return verificationResult;
        } catch (Exception e) {
            String errorMessage = String.format("An error occurred while verifying the signature using the " +
                            "algorithm '%s' and the Java Security API provider '%s'; %s ; %s ",
                    algorithm, javaSecurityAPIProvider, cryptoContext, certificateInfo);
            // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new CryptoException(errorMessage, e);
        }
    }

    /**
     * Returns the {@link Certificate} based on the given {@link CryptoContext}
     * When needed the certificate is retrieved using Carbon KeyStoreManager.
     *
     * @param cryptoContext   The context information which is used to discover the public key of the external entity.
     * @param certificateInfo The information which is needed to retrieve the certificate.
     *                        If this information is not sufficient the {@link CryptoContext} will be used to
     *                        get more information.
     * @return The {@link Certificate} relates with the given context.
     * @throws CryptoException If something unexpected happens during certificate discovery.
     */
    @Override
    public Certificate getCertificate(CryptoContext cryptoContext, CertificateInfo certificateInfo)
            throws CryptoException {

        failIfContextInformationIsMissing(cryptoContext);

        if (certificateInfo.getCertificate() != null) {
            return certificateInfo.getCertificate();
        } else {

            KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(cryptoContext.getTenantId());

            try {
                if (SUPER_TENANT_ID == cryptoContext.getTenantId()) {

                    if (log.isDebugEnabled()) {
                        log.debug("Looking for the certificate in the super tenant using " + certificateInfo);
                    }

                    KeyStore keyStore = keyStoreManager.getPrimaryKeyStore();
                    return keyStore.getCertificate(certificateInfo.getCertificateAlias());
                } else {

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Looking for the certificate in the tenant '%s' using %s",
                                cryptoContext.getTenantDomain(), certificateInfo));
                    }

                    String keyStoreName = getTenantKeyStoreName(cryptoContext.getTenantDomain());
                    KeyStore keyStore = keyStoreManager.getKeyStore(keyStoreName);
                    return keyStore.getCertificate(certificateInfo.getCertificateAlias());
                }
            } catch (Exception e) { // KeyStoreManager throws 'Exception'.

                String errorMessage = "An error occurred while retrieving the certificate from the key store.";

                // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
                if (log.isDebugEnabled()) {
                    log.debug(errorMessage, e);
                }

                throw new CryptoException(errorMessage, e);
            }
        }
    }

    /**
     * Returns the {@link PrivateKey} based on the given {@link CryptoContext}
     * The certificate is retrieved using Carbon KeyStoreManager.
     *
     * @param cryptoContext  The context information which is used to discover the applicable private key.
     * @param privateKeyInfo The information which is needed to retrieve the private key.
     *                       If this information is not sufficient, the {@link CryptoContext} will be used to
     *                       get more information.
     * @return The {@link Certificate} relates with the given context.
     * @throws CryptoException If something unexpected happens during private key discovery.
     */
    @Override
    public PrivateKey getPrivateKey(CryptoContext cryptoContext, PrivateKeyInfo privateKeyInfo) throws CryptoException {

        failIfContextInformationIsMissing(cryptoContext);

        try {

            KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(cryptoContext.getTenantId());

            PrivateKey privateKey;
            if (SUPER_TENANT_ID == cryptoContext.getTenantId()) {

                if (log.isDebugEnabled()) {
                    log.debug("Looking for the private key in the super tenant using " + privateKeyInfo);
                }

                KeyStore keyStore = keyStoreManager.getPrimaryKeyStore();
                privateKey = (PrivateKey) keyStore.getKey(privateKeyInfo.getKeyAlias(),
                        privateKeyInfo.getKeyPassword().toCharArray());
            } else {

                if (log.isDebugEnabled()) {
                    log.debug(String.format("Looking for the private key in the tenant '%s' using %s",
                            cryptoContext.getTenantDomain(), privateKeyInfo));
                }

                String keyStoreName = getTenantKeyStoreName(cryptoContext.getTenantDomain());

                if (log.isDebugEnabled()) {
                    log.debug(String.format("Derived Key Store name of the the tenant '%s' is %s",
                            cryptoContext.getTenantDomain(), keyStoreName));
                }

                privateKey = (PrivateKey) keyStoreManager.getPrivateKey(keyStoreName, privateKeyInfo.getKeyAlias());
            }

            return privateKey;

        } catch (Exception e) { // KeyStoreManager throws 'Exception'.

            String errorMessage = "An error occurred while retrieving the private key from the key store.";

            // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }

            throw new CryptoException(errorMessage, e);
        }
    }

    /**
     * Computes and returns the {@link HybridEncryptionOutput} based on provided {@link HybridEncryptionInput}
     * Hybrid encryption is carried out using JCE providers.
     *
     * @param hybridEncryptionInput Input data for hybrid encryption.*
     * @param symmetricAlgorithm    The symmetric encryption/decryption algorithm.
     * @param asymmetricAlgorithm   The asymmetric encryption/decryption algorithm.
     * @param jceSecurityProvider   The Java Security API provider.
     * @param cryptoContext         The context information which is used to discover the public key of the external entity.
     * @return {@link HybridEncryptionOutput} cipher text with required parameters
     * @throws CryptoException
     */
    @Override
    public HybridEncryptionOutput hybridEncrypt(HybridEncryptionInput hybridEncryptionInput, String symmetricAlgorithm, String asymmetricAlgorithm,
                                                String jceSecurityProvider, CryptoContext cryptoContext,
                                                CertificateInfo certificateInfo) throws CryptoException {

        String[] resolvedSymmetricAlgorithmSpec = resolveSymmetricAlgorithm(symmetricAlgorithm);
        int keyLength = 16;
        if (resolvedSymmetricAlgorithmSpec[1] != null) {
            keyLength = Integer.parseInt(resolvedSymmetricAlgorithmSpec[1]) / 8;
        }
        symmetricAlgorithm = resolvedSymmetricAlgorithmSpec[2];
        byte[] keyValue = new byte[keyLength];
        random.nextBytes(keyValue);
        SecretKeySpec symmetricKey = new SecretKeySpec(keyValue, resolvedSymmetricAlgorithmSpec[0]);
        try {
            Cipher cipher;
            if (StringUtils.isBlank(jceSecurityProvider)) {
                cipher = Cipher.getInstance(symmetricAlgorithm);
            } else {
                cipher = Cipher.getInstance(symmetricAlgorithm, jceSecurityProvider);
            }
            AlgorithmParameterSpec algoParams = resolveSymmetricAlgorithmParameters(symmetricAlgorithm);
            cipher.init(Cipher.ENCRYPT_MODE, symmetricKey, algoParams);

            if (hybridEncryptionInput.getAuthData() != null) {
                cipher.updateAAD(hybridEncryptionInput.getAuthData());
            }
            byte[] encryptedData = cipher.doFinal(hybridEncryptionInput.getPlainData());
            byte[] encryptedKey = encrypt(keyValue, asymmetricAlgorithm, jceSecurityProvider, cryptoContext,
                    certificateInfo);
            if (hybridEncryptionInput.getAuthData() != null) {
                int tagPos = encryptedData.length - ((GCMParameterSpec) algoParams).getTLen() / 8;
                byte[] authTag = subArray(encryptedData, tagPos, ((GCMParameterSpec) algoParams).getTLen() / 8);
                byte[] cipherData = subArray(encryptedData, 0, tagPos);
                return new HybridEncryptionOutput(cipherData, encryptedKey, hybridEncryptionInput.getAuthData(),
                        authTag, algoParams);
            } else {
                return new HybridEncryptionOutput(encryptedData, encryptedKey, algoParams);
            }
        } catch (Exception e) {
            String errorMessage = String.format("An error occurred while hybrid encrypting data using the " +
                            "asymmetric algorithm '%s' and symmetric algorithm '%s' with the " +
                            "Java Security API provider '%s'; %s ; %s", asymmetricAlgorithm, symmetricAlgorithm,
                    jceSecurityProvider, cryptoContext, certificateInfo);
            // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new CryptoException(errorMessage, e);
        }
    }

    /**
     * Computes and return clear data based on provided {@link HybridEncryptionOutput}
     * Hybrid decryption is carried out using JCE providers.
     *
     * @param hybridEncryptionOutput {@link HybridEncryptionOutput} ciphered data with parameters.
     * @param symmetricAlgorithm     The symmetric encryption/decryption algorithm.
     * @param asymmetricAlgorithm    The asymmetric encryption/decryption algorithm.
     * @param jceSecurityProvider    The Java Security API provider.
     * @param cryptoContext          The context information which is used to discover the public key of the external entity.
     * @return the decrypted data
     * @throws CryptoException
     */
    @Override
    public byte[] hybridDecrypt(HybridEncryptionOutput hybridEncryptionOutput, String symmetricAlgorithm, String asymmetricAlgorithm,
                                String jceSecurityProvider, CryptoContext cryptoContext, PrivateKeyInfo privateKeyInfo)
            throws CryptoException {

        String[] resolvedSpecification = resolveSymmetricAlgorithm(symmetricAlgorithm);
        symmetricAlgorithm = resolvedSpecification[2];
        byte[] decryptedKey = decrypt(hybridEncryptionOutput.getEncryptedSymmetricKey(), asymmetricAlgorithm,
                jceSecurityProvider, cryptoContext, privateKeyInfo);
        SecretKeySpec decryptionKey = new SecretKeySpec(decryptedKey, resolvedSpecification[0]);
        try {
            Cipher cipher;
            if (StringUtils.isBlank(jceSecurityProvider)) {
                cipher = Cipher.getInstance(symmetricAlgorithm);
            } else {
                cipher = Cipher.getInstance(symmetricAlgorithm, jceSecurityProvider);
            }
            if (hybridEncryptionOutput.getParameterSpec() == null) {
                cipher.init(Cipher.DECRYPT_MODE, decryptionKey);
            } else {
                cipher.init(Cipher.DECRYPT_MODE, decryptionKey, hybridEncryptionOutput.getParameterSpec());
            }
            if ((hybridEncryptionOutput.getAuthData() != null) && (hybridEncryptionOutput.getAuthTag() != null)) {
                cipher.updateAAD(hybridEncryptionOutput.getAuthData());
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputStream.write(hybridEncryptionOutput.getCipherData());
                outputStream.write(hybridEncryptionOutput.getAuthTag());
                return cipher.doFinal(outputStream.toByteArray());
            } else {
                return cipher.doFinal(hybridEncryptionOutput.getCipherData());
            }
        } catch (Exception e) {
            String errorMessage = String.format("An error occurred while hybrid decrypting using the " +
                            "symmetric algorithm : '%s' and asymmetric algorithm : '%s'" +
                            " with the Java Security API provider : '%s'; %s ; %s", symmetricAlgorithm,
                    asymmetricAlgorithm, jceSecurityProvider, cryptoContext, privateKeyInfo);
            // Log the exception from client libraries, to avoid missing information if callers code doesn't log it
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new CryptoException(errorMessage, e);
        }
    }

    private void failIfContextInformationIsMissing(CryptoContext cryptoContext) throws CryptoException {

        if (cryptoContext.getTenantId() == 0 || StringUtils.isBlank(cryptoContext.getTenantDomain())) {
            throw new CryptoException("Tenant information is missing in the crypto context.");
        }
    }

    private String getTenantKeyStoreName(String tenantDomain) {

        return tenantDomain.trim().replace(".", "-") + ".jks";
    }

    private AlgorithmParameterSpec resolveSymmetricAlgorithmParameters(String symmetricAlgorithm) throws CryptoException {

        AlgorithmParameterSpec algoParams = null;
        if (symmetricAlgorithm.contains("AES")) {
            byte[] iv = new byte[16];
            random.nextBytes(iv);
            if (symmetricAlgorithm.contains("GCM")) {
                algoParams = new GCMParameterSpec(128, iv);
            } else if (symmetricAlgorithm.contains("OFB") || symmetricAlgorithm.contains("CBC")
                    || symmetricAlgorithm.contains("CFB")) {
                algoParams = new IvParameterSpec(iv);
            }
        } else if (symmetricAlgorithm.contains("DES")) {
            byte[] iv = new byte[8];
            random.nextBytes(iv);
            algoParams = new IvParameterSpec(iv);
        } else {
            String errorMessage = String.format("'%s' symmetric algorithm is not supported by '%s' " +
                    "external provider for hybrid encryption.", symmetricAlgorithm, this.getClass().getName());
            throw new CryptoException(errorMessage);
        }
        return algoParams;
    }

    private byte[] subArray(byte[] byteArray, int beginIndex, int length) {

        byte[] subArray = new byte[length];
        System.arraycopy(byteArray, beginIndex, subArray, 0, subArray.length);
        return subArray;
    }

    private String[] resolveSymmetricAlgorithm(String symmetricAlgorithm) {

        String[] algorithmSpecification = symmetricAlgorithm.split("/");
        String[] keySpecification = algorithmSpecification[0].split("_");
        String keyLength = null;
        if (keySpecification.length > 1) {
            keyLength = keySpecification[1];
        }
        algorithmSpecification[0] = keySpecification[0];
        symmetricAlgorithm = String.join("/", algorithmSpecification);
        return new String[]{keySpecification[0], keyLength, symmetricAlgorithm};
    }
}

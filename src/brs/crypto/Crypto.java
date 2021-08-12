package brs.crypto;

import esg.kit.crypto.EsgCrypto;
import esg.kit.entity.EsgID;
import org.bouncycastle.jcajce.provider.digest.MD5;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Crypto {
  static final EsgCrypto esgCrypto = EsgCrypto.getInstance();

  private Crypto() {
  } //never

  public static MessageDigest sha256() {
    return esgCrypto.getSha256();
  }

  public static MessageDigest shabal256() {
    return esgCrypto.getShabal256();
  }

  public static MessageDigest ripemd160() {
    return esgCrypto.getRipeMD160();
  }

  public static MessageDigest md5() {// TODO unit test
    try {
      return MessageDigest.getInstance("MD5"); // TODO esgkit4j integration
    } catch (NoSuchAlgorithmException e) {
      return new MD5.Digest();
    }
  }

  public static byte[] getPublicKey(String secretPhrase) {
    return esgCrypto.getPublicKey(secretPhrase);
  }

  public static byte[] getPrivateKey(String secretPhrase) {
    return esgCrypto.getPrivateKey(secretPhrase);
  }

  public static byte[] sign(byte[] message, String secretPhrase) {
      return esgCrypto.sign(message, secretPhrase);
  }

  public static boolean verify(byte[] signature, byte[] message, byte[] publicKey, boolean enforceCanonical) {
      return esgCrypto.verify(signature, message, publicKey, enforceCanonical);
  }

  public static byte[] aesEncrypt(byte[] plaintext, byte[] myPrivateKey, byte[] theirPublicKey) {
    return esgCrypto.aesSharedEncrypt(plaintext, myPrivateKey, theirPublicKey);
  }

  public static byte[] aesEncrypt(byte[] plaintext, byte[] myPrivateKey, byte[] theirPublicKey, byte[] nonce) {
    return esgCrypto.aesSharedEncrypt(plaintext, myPrivateKey, theirPublicKey, nonce);
  }

  public static byte[] aesDecrypt(byte[] ivCiphertext, byte[] myPrivateKey, byte[] theirPublicKey) {
    return esgCrypto.aesSharedDecrypt(ivCiphertext, myPrivateKey, theirPublicKey);
  }

  public static byte[] aesDecrypt(byte[] ivCiphertext, byte[] myPrivateKey, byte[] theirPublicKey, byte[] nonce) {
    return esgCrypto.aesSharedDecrypt(ivCiphertext, myPrivateKey, theirPublicKey, nonce);
  }

  public static byte[] getSharedSecret(byte[] myPrivateKey, byte[] theirPublicKey) {
    return esgCrypto.getSharedSecret(myPrivateKey, theirPublicKey);
  }

  public static String rsEncode(long id) {
    return esgCrypto.rsEncode(EsgID.fromLong(id));
  }

  public static long rsDecode(String rsString) {
    return esgCrypto.rsDecode(rsString).getSignedLongId();
  }
}

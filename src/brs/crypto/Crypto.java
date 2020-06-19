package brs.crypto;

import amz.kit.crypto.AmzCrypto;
import amz.kit.entity.AmzID;
import org.bouncycastle.jcajce.provider.digest.MD5;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Crypto {
  static final AmzCrypto amzCrypto = AmzCrypto.getInstance();

  private Crypto() {
  } //never

  public static MessageDigest sha256() {
    return amzCrypto.getSha256();
  }

  public static MessageDigest shabal256() {
    return amzCrypto.getShabal256();
  }

  public static MessageDigest ripemd160() {
    return amzCrypto.getRipeMD160();
  }

  public static MessageDigest md5() {// TODO unit test
    try {
      return MessageDigest.getInstance("MD5"); // TODO amzkit4j integration
    } catch (NoSuchAlgorithmException e) {
      return new MD5.Digest();
    }
  }

  public static byte[] getPublicKey(String secretPhrase) {
    return amzCrypto.getPublicKey(secretPhrase);
  }

  public static byte[] getPrivateKey(String secretPhrase) {
    return amzCrypto.getPrivateKey(secretPhrase);
  }

  public static byte[] sign(byte[] message, String secretPhrase) {
      return amzCrypto.sign(message, secretPhrase);
  }

  public static boolean verify(byte[] signature, byte[] message, byte[] publicKey, boolean enforceCanonical) {
      return amzCrypto.verify(signature, message, publicKey, enforceCanonical);
  }

  public static byte[] aesEncrypt(byte[] plaintext, byte[] myPrivateKey, byte[] theirPublicKey) {
    return amzCrypto.aesSharedEncrypt(plaintext, myPrivateKey, theirPublicKey);
  }

  public static byte[] aesEncrypt(byte[] plaintext, byte[] myPrivateKey, byte[] theirPublicKey, byte[] nonce) {
    return amzCrypto.aesSharedEncrypt(plaintext, myPrivateKey, theirPublicKey, nonce);
  }

  public static byte[] aesDecrypt(byte[] ivCiphertext, byte[] myPrivateKey, byte[] theirPublicKey) {
    return amzCrypto.aesSharedDecrypt(ivCiphertext, myPrivateKey, theirPublicKey);
  }

  public static byte[] aesDecrypt(byte[] ivCiphertext, byte[] myPrivateKey, byte[] theirPublicKey, byte[] nonce) {
    return amzCrypto.aesSharedDecrypt(ivCiphertext, myPrivateKey, theirPublicKey, nonce);
  }

  public static byte[] getSharedSecret(byte[] myPrivateKey, byte[] theirPublicKey) {
    return amzCrypto.getSharedSecret(myPrivateKey, theirPublicKey);
  }

  public static String rsEncode(long id) {
    return amzCrypto.rsEncode(AmzID.fromLong(id));
  }

  public static long rsDecode(String rsString) {
    return amzCrypto.rsDecode(rsString).getSignedLongId();
  }
}

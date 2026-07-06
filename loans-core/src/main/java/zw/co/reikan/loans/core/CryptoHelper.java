package zw.co.reikan.loans.core;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class CryptoHelper {

    private final IvParameterSpec ivspec;
    private final SecretKeySpec keyspec;
    private final Cipher cipher;


    public static void main(String[] args) {
        try {
            final String secretKey = "5#Gq8H@1*2$7lN!z";
            final String encrypt = CryptoHelper.encrypt("thomas|nyagwaya|24-Nov-2023|67-7867687786-09", secretKey);
            System.out.println(encrypt);
            final String decrypt = CryptoHelper.decrypt(encrypt, secretKey);
            System.out.println(decrypt);
        }
        catch (Exception ex){
            ex.printStackTrace();
        }
    }

    public CryptoHelper(String secretKey) throws Exception {
        ivspec = new IvParameterSpec(secretKey.getBytes());
        keyspec = new SecretKeySpec(secretKey.getBytes(), "AES");
        cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    }

    public static String encrypt(String data, String secretKey) throws Exception {
        CryptoHelper enc = new CryptoHelper(secretKey);
        return Base64.getEncoder().encodeToString(enc.encryptInternal(data));
    }

    public static String decrypt(String data, String secretKey) throws Exception {
        CryptoHelper enc = new CryptoHelper(secretKey);
        return new String(enc.decryptInternal(data));
    }

    private byte[] encryptInternal(String text) throws Exception {
        if (text == null || text.length() == 0) {
            throw new Exception("Empty string");
        }

        byte[] encrypted = null;
        try {
            cipher.init(Cipher.ENCRYPT_MODE, keyspec, ivspec);
            encrypted = cipher.doFinal(text.getBytes());
        } catch (Exception e) {
            throw new Exception("[encrypt] " + e.getMessage());
        }
        return encrypted;
    }

    private byte[] decryptInternal(String code) throws Exception {
        if (code == null || code.length() == 0) {
            throw new Exception("Empty string");
        }

        byte[] decrypted = null;
        try {
            cipher.init(Cipher.DECRYPT_MODE, keyspec, ivspec);
            decrypted = cipher.doFinal(Base64.getDecoder().decode(code));
        } catch (Exception e) {
            throw new Exception("[decrypt] " + e.getMessage());
        }
        return decrypted;
    }
}

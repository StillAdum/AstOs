import com.astos.link.LinkCrypto;
public class CryptoDriver {public static void main(String[] args)throws Exception{LinkCrypto c=new LinkCrypto("token","challenge","shared");String line=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,"UTF-8")).readLine();System.out.println(args[0].equals("seal")?c.seal(line):c.open(line));}}

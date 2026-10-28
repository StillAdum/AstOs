import com.astos.shell.CommandRouter;
import java.lang.reflect.Proxy;
import java.util.Arrays;

public class CommandRouterTest {
    static String method; static Object[] values; static boolean sticky;
    static CommandRouter.Actions actions = (CommandRouter.Actions) Proxy.newProxyInstance(
        CommandRouter.Actions.class.getClassLoader(), new Class[]{CommandRouter.Actions.class}, (p, m, args) -> {
            if (m.getName().equals("pcSelected")) return sticky;
            method = m.getName(); values = args; return null;
        });
    static void test(String phrase, String expected, Object... args) {
        method = ""; values = null;
        CommandRouter.route(phrase, actions);
        if (!method.equals(expected) || !Arrays.equals(values == null ? new Object[0] : values, args))
            throw new AssertionError(phrase + " => " + method + " " + Arrays.toString(values));
    }
    public static void main(String[] args) {
        test("hey ast play never gonna give you up on spotify in my pc", "pcAction", "play", "never gonna give you up", false);
        test("hey ast play next song in my pc", "pcAction", "next", "next song", false);
        test("hey ast play songs through my pc until it is disconnected", "pcAction", "play", "", true);
        test("hey ast play despacito", "playSong", "despacito");
        test("hey astos play bohemian rhapsody", "playSong", "bohemian rhapsody");
        test("hey spotify play never gonna give you up", "playSong", "never gonna give you up");
        test("pause", "mediaAction", "pause", 0); // route returns spoken message too
        sticky = true;
        test("next song", "pcAction", "next", "next song", false);
        test("please play bohemian rhapsody", "pcAction", "play", "bohemian rhapsody", false);
        test("stop timer", "askGroq", "stop timer");
        test("set playback to my pc", "playbackTarget", true);
        test("set playback to tablet", "playbackTarget", false);
        test("play run it back", "pcAction", "play", "run it back", false);
        test("play https://youtu.be/AbC123_XyZ", "pcAction", "play", "https://youtu.be/AbC123_XyZ", false);
        sticky = false;
        test("play run it back", "playSong", "run it back");
        test("play https://youtu.be/AbC123_XyZ", "playSong", "https://youtu.be/AbC123_XyZ");
        test("could you play despacito", "playSong", "despacito");
        test("android settings", "openSettings");
        System.out.println("COMMAND ROUTER PASS");
    }
}

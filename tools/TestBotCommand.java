import com.flybrain.comm.BotCommand;
import com.flybrain.comm.BotCommand.Action;
import com.flybrain.comm.BotCommand.Parsed;

/** BotCommand 解析器单测（纯 Java，无 Minecraft 依赖） */
public class TestBotCommand {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // —— 用户给的原例 ——
        check("ALEX，去探索一下前面", "ALEX", Action.EXPLORE);
        check("ALEX，去探索一下", "ALEX", Action.EXPLORE);

        // —— 各种分隔符 ----
        check("STEVE 过来", "STEVE", Action.COME);
        check("NICK，停下", "NICK", Action.STOP);
        check("kai， attack", "kai", Action.ATTACK);
        check("ZURI，跳一下", "ZURI", Action.JUMP);

        // —— 英文指令 ----
        check("alex, explore ahead", "alex", Action.EXPLORE);
        check("steve come here", "steve", Action.COME);
        check("nick stop", "nick", Action.STOP);
        check("kai jump", "kai", Action.JUMP);

        // —— 边界：不该识别的 ----
        checkNoCmd("你好");
        checkNoCmd("今天天气怎么样");
        checkNoCmd("钻石在哪里挖");         // 这是 wiki 问题不是指令
        checkNoCmd("123 456");

        // —— 名字长度限制（防误判）——
        checkNoCmd("这是一个很长的中文句子没有英文名开头");
        check("AB12，走走", "AB12", Action.WANDER);   // 允许数字

        System.out.println("\n" + "=".repeat(46));
        System.out.printf("通过 %d / %d%n", pass, pass + fail);
        if (fail == 0) System.out.println("✓ BotCommand 全部通过");
    }

    static void check(String input, String expectName, Action expectAction) {
        Parsed p = BotCommand.parse(input);
        boolean nameOk = expectName.equalsIgnoreCase(p.botName());
        boolean actOk = p.action() == expectAction;
        if (nameOk && actOk) {
            pass++;
            System.out.printf("  ✓ %-28s → %s / %s%n",
                    "\"" + input + "\"", p.botName(), p.action());
        } else {
            fail++;
            System.out.printf("  ✗ %-28s → %s / %s (期望 %s / %s)%n",
                    "\"" + input + "\"", p.botName(), p.action(),
                    expectName, expectAction);
        }
    }

    static void checkNoCmd(String input) {
        Parsed p = BotCommand.parse(input);
        if (p.action() == Action.NONE) {
            pass++;
            System.out.printf("  ✓ %-28s → 非指令（正确）%n", "\"" + input + "\"");
        } else {
            fail++;
            System.out.printf("  ✗ %-28s → 误判为 %s%n",
                    "\"" + input + "\"", p.action());
        }
    }
}

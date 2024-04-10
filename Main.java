public class Main {
    String foo(int a) {
        if (a > 5) {
            if (a > 7) {
                return "> 7";
            } else {
                return "5 to 7";
            }
        } else {
            if (a < 3) {
                return "< 3";
            }
        }
        return "";
    }
}

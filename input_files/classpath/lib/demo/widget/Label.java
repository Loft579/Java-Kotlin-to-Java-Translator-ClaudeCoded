package demo.widget;

import java.util.Arrays;
import java.util.List;

/* A stand-in for a compiled library such as android.jar, written in the style of
   android.widget.TextView: getters and setters, an 'is' accessor, constants and a
   public field. tests.sh compiles it on its own and hands it over with --classpath,
   so the translator only ever sees its .class files. */
public class Label {
    public static final int VISIBLE = 0;
    public static final int GONE = 8;

    /* A public field and a getter of the same name: Kotlin reads the field. */
    public int tag;

    private String text;
    private boolean enabled = true;
    private int visibility = VISIBLE;

    public Label(String text) { this.text = text; }

    public static Label of(String text) { return new Label(text); }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getVisibility() { return visibility; }
    public void setVisibility(int visibility) { this.visibility = visibility; }

    public int getTag() { return -1; }

    /* Kotlin calls this property 'url', not 'uRL'. */
    public String getURL() { return "https://example.com/" + text; }

    public List<String> getWords() { return Arrays.asList(text.split(" ")); }

    public Label copy() { return new Label(text); }

    public int measure(int width) { return width * 2; }
    public double measure(double width) { return width / 2; }
}

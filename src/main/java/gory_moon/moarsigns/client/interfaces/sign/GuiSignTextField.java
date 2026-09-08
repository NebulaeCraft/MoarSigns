package gory_moon.moarsigns.client.interfaces.sign;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatAllowedCharacters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuiSignTextField extends GuiTextField {

    private static final Pattern SPECIAL = Pattern.compile("(\\{" + (char) 8747 + "[0-9a-z]\\})");

    private int maxRowLength = 90;

    public GuiSignTextField(int id, FontRenderer p_i1032_1_, int p_i1032_2_, int p_i1032_3_, int p_i1032_4_, int p_i1032_5_) {
        super(id, p_i1032_1_, p_i1032_2_, p_i1032_3_, p_i1032_4_, p_i1032_5_);
    }

    /**
     * The width the sign will actually draw this line with. The {<8747>x} markers are turned into
     * the <167>x codes first, so bold text is measured a pixel per character wider, exactly like
     * the renderer measures it. Measuring the marker form instead let a bold line grow past the
     * row and lose its last characters again on every save.
     */
    private int getRenderedWidth(String s) {
        return fontRenderer.getStringWidth(GuiMoarSign.getSignTextWithColor(new String[]{s})[0].getUnformattedText());
    }

    /**
     * Splits the line into style markers and single characters, so a marker is never cut in half.
     */
    private String nextUnit(Matcher m, String s, int pos) {
        return m.find(pos) && m.start() == pos ? m.group() : s.substring(pos, pos + 1);
    }

    @Override
    public void setText(String text) {
        // Loading a line never shortens it. A sign written before the row got wider styles, or one
        // pasted from a smaller row, is kept whole and simply clipped when it is drawn - writeText
        // is what stops the row from growing any further.
        this.text = text;
        this.setCursorPositionEnd();
    }

    @Override
    public void writeText(String text) {
        String s2 = ChatAllowedCharacters.filterAllowedCharacters(text);
        int i = Math.min(this.cursorPosition, this.selectionEnd);
        int j = Math.max(this.cursorPosition, this.selectionEnd);

        String start = this.text.substring(0, i);
        String end = this.text.substring(j);

        Matcher m = SPECIAL.matcher(s2);
        StringBuilder written = new StringBuilder();

        for (int pos = 0; pos < s2.length(); ) {
            String unit = nextUnit(m, s2, pos);

            // Style markers take no room on the sign itself, so they always fit. Everything else
            // is checked against the whole rebuilt line instead of against the leftover width,
            // which is what used to let one character too many through on a bold row.
            if (!SPECIAL.matcher(unit).matches() && getRenderedWidth(start + written + unit + end) > this.maxRowLength)
                break;

            written.append(unit);
            pos += unit.length();
        }

        this.text = start + written + end;
        this.moveCursorBy(i - this.getSelectionEnd() + written.length());
    }

    @Override
    public void deleteFromCursor(int p_146175_1_) {
        if (this.text.length() != 0) {
            if (this.selectionEnd != this.cursorPosition) {
                this.writeText("");
            } else {
                boolean flag = p_146175_1_ < 0;

                char[][] position = {{'{'}, {(char) 8747}, {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'k', 'l', 'm', 'n', 'o', 'r'}, {'}'}};

                int offset = getCharIndex(position, this.text.charAt(this.cursorPosition + (flag && cursorPosition != 0 ? p_146175_1_ : ((!flag && this.cursorPosition == this.text.length()) ? -1 : 0))));

                if (offset > -1 && (flag && (0 <= cursorPosition - offset - 1) && this.text.length() > this.cursorPosition + (2 - (offset)) && isSpecial(this.text.substring(this.cursorPosition - offset - 1, cursorPosition + (3 - offset)))) || (!flag && 0 <= cursorPosition - offset) && this.text.length() > this.cursorPosition + (3 - offset + 1) && isSpecial(this.text.substring(this.cursorPosition - offset, cursorPosition + (3 - offset + 1)))) {

                    this.selectionEnd = flag ? (cursorPosition + (3 - offset)) : (cursorPosition + (3 - offset + 1));
                    this.cursorPosition = flag ? (cursorPosition - offset - 1) : (cursorPosition - offset);

                    this.writeText("");

                } else {

                    int j = flag ? this.cursorPosition + p_146175_1_ : this.cursorPosition;
                    int k = flag ? this.cursorPosition : this.cursorPosition + p_146175_1_;
                    String s = "";

                    if (j >= 0) {
                        s = this.text.substring(0, j);
                    }

                    if (k < this.text.length()) {
                        s = s + this.text.substring(k);
                    }

                    this.text = s;

                    if (flag) {
                        this.moveCursorBy(p_146175_1_);
                    }
                }
            }
        }
    }

    public void setMaxRowLength(int maxRowLength) {
        this.maxRowLength = maxRowLength;
    }

    private boolean isSpecial(String s) {
        return SPECIAL.matcher(s).matches();
    }

    private int getCharIndex(char[][] arr, char c) {
        int pos = -1;

        for (int i = 0; i < arr.length; i++) {
            for (int j = 0; j < arr[i].length; j++) {
                pos = arr[i][j] == c ? i : pos;
            }
        }

        return pos;
    }
}

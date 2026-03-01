package es.uc3m.miaa.utils;

public class Entity {

    String type;
    String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return new String(getType() + "::" + getText());
    }

    public static Entity buildFromText(String text) throws Exception {

        String entTokens[] = text.split("::");
        if (entTokens.length != 2) {
            throw new Exception("Bad annotation file. Unable to build entity from text " + text);
        }

        Entity ent = new Entity();
        ent.setType(entTokens[0].trim());
        ent.setText(entTokens[1].trim());

        return ent;
    }

    public int compareTo(Object o) {

        if (o instanceof Entity) {
            Entity e = (Entity) o;
            if (e.getType().equals(this.getType()) && e.getText().equals(this.getText())) {
                return 0;
            }
            return e.getText().compareTo(this.getText());
        } else {
            throw new UnsupportedOperationException("It is not possible to compare the object");
        }
    }

    @Override
    public boolean equals(Object o) {

        if (o instanceof Entity) {
            if (this.compareTo(o) == 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 23 * hash + (this.type != null ? this.type.hashCode() : 0);
        hash = 23 * hash + (this.text != null ? this.text.hashCode() : 0);
        return hash;
    }
}

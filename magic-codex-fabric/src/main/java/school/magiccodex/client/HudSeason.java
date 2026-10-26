package school.magiccodex.client;

/** Display-only season selection until the server's season system is connected. */
public enum HudSeason {
    SPRING("spring", "봄", 121, 111, 1010, 951),
    SUMMER("summer", "여름", 79, 68, 1094, 1083),
    AUTUMN("autumn", "가을", 140, 126, 1006, 997),
    WINTER("winter", "겨울", 134, 83, 985, 1080);

    public record Crop(int x, int y, int width, int height) {}
    private final String id, label;
    private final Crop crop;
    HudSeason(String id, String label, int x, int y, int width, int height) {
        this.id=id; this.label=label; this.crop=new Crop(x,y,width,height);
    }
    public String id(){return id;}
    public String label(){return label;}
    public String texture(){return "magiccodex:textures/hud/seasons/"+id+".png";}
    public Crop crop(){return crop;}
}

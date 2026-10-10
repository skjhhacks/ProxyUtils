package bwproxy.game;

/** Numeric 1.8 item ids (from minecraft-data pc/1.8 items.json). */
public final class Items {
	private Items() {
	}
	public static final int OBSIDIAN = 49;
	public static final int TNT = 46;
	public static final int BOW = 261;
	public static final int IRON_SWORD = 267;
	public static final int DIAMOND_SWORD = 276;
	public static final int DIAMOND_PICKAXE = 278;
	public static final int STICK = 280;
	public static final int GOLDEN_PICKAXE = 285;
	public static final int GOLDEN_APPLE = 322;
	public static final int WATER_BUCKET = 326;
	public static final int MILK_BUCKET = 335;
	public static final int SNOWBALL = 332;
	public static final int EGG = 344;
	public static final int ENDER_PEARL = 368;
	public static final int POTION = 373;
	public static final int FIRE_CHARGE = 385;

	public static boolean isSword(final int id) {
		return id == 268 || id == 272 || id == 267 || id == 283 || id == 276;
	}

	public static boolean isArmor(final int id) {
		return id >= 298 && id <= 317;
	}

	/** 0 leather, 1 chainmail, 2 iron, 3 diamond, 4 gold. */
	public static int armorMaterial(final int id) {
		return (id - 298) / 4;
	}
}

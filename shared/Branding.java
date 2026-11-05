package fcp.shared;

/** Branding constants shared across desktop and Android builds. */
public final class Branding {
  private Branding() {}

  /** Public-facing product name (window titles, store, website). */
  public static final String PRODUCT_NAME = "FCP Launcher";

  /**
   * Internal full expansion for the hidden in-app easter egg (not the formal About page) only.
   * Do not put this on the website, README, or store listing.
   */
  public static final String PRODUCT_FULL_NAME = "Fuck Chinese Parental";

  public static final String PRODUCT_SHORT_NAME = "FCP";
}

package cc.rocketscience.receipts.ui.help

/**
 * In-app documentation, shown from Settings.
 *
 * Deliberately prose a user can read start to finish rather than a feature list: the parts
 * people get wrong — that restoring replaces everything, that a shared report is a file you
 * hand over yourself, that the same file works on the other kind of phone — are the parts
 * that need explaining, not the buttons.
 *
 * The iOS screen carries the same text, word for word. If you change one, change both.
 */
data class HelpTopic(val title: String, val body: String)

val helpTopics: List<HelpTopic> = listOf(
    HelpTopic(
        "The idea",
        "A report is a container — a trip, a project, a month. Receipts go inside it, and " +
            "the report totals itself. Everything lives on this device: no account, no " +
            "sign-up, nothing sent anywhere unless you choose to send it.",
    ),
    HelpTopic(
        "Adding receipts",
        "The + button on a report adds one. Take a photo or choose an existing image, then " +
            "drag the corners to crop — or skip the photo and type an amount. Each receipt " +
            "has a description, an amount and a date. Tap a photo to view it full screen; " +
            "pinch or double-tap to zoom.",
    ),
    HelpTopic(
        "Ordering",
        "Tap the reorder button on the reports list to drag reports into whatever order you " +
            "like. New reports arrive at the top.",
    ),
    HelpTopic(
        "Exporting a report",
        "From a report's menu:\n\n" +
            "• Export PDF — a summary table, then one page per receipt image.\n" +
            "• Export ZIP — report.csv plus the images, numbered to match the rows.\n\n" +
            "Both open the system file picker, so they can go to Google Drive, local " +
            "storage, or anywhere else listed there.",
    ),
    HelpTopic(
        "Backing up",
        "Settings → Back up to a file writes a single ZIP holding every report, receipt and " +
            "photo. Save it wherever you like — Google Drive is just one of the places the " +
            "picker offers.\n\n" +
            "Restore from a file reads one back. It replaces everything currently in the " +
            "app; there is no merge and no undo. The file is checked before anything is " +
            "touched, so a damaged or too-new backup is refused rather than half-imported.",
    ),
    HelpTopic(
        "Sharing a report with someone",
        "Sharing is a file you hand over yourself — the app has no accounts and talks to no " +
            "service.\n\n" +
            "1. Long-press a report → Share this report, and save the file to Google Drive.\n" +
            "2. Share that file with the other person, the way you would share any Drive " +
            "file.\n" +
            "3. They open + → Add a shared report… and pick it.\n\n" +
            "After that both devices are bound to the same file. Sync now in the report's " +
            "menu merges and republishes, and bound files are re-read whenever the app opens.",
    ),
    HelpTopic(
        "How sharing merges",
        "Both people can work at the same time. Each sync reads the file, merges it with " +
            "what is here, and writes the result back.\n\n" +
            "• Changes to different receipts both survive.\n" +
            "• Changes to the same receipt resolve to whichever edit was made later.\n" +
            "• Deleting a receipt removes it for everyone, rather than it reappearing on the " +
            "next sync.\n" +
            "• Photos travel inside the file, so there is nothing to send alongside it.\n\n" +
            "Every sync reports what it did — added, updated, already matched, deleted by " +
            "others, sent from here.",
    ),
    HelpTopic(
        "Android and iPhone together",
        "Backups and shared reports work across both. A backup written on Android restores " +
            "on an iPhone or iPad, and the other way round. A shared report can have one " +
            "person on Android and the other on iOS; both read and write the same file.\n\n" +
            "Keep both devices on a current version. A backup from a newer release is " +
            "refused by an older one rather than imported incorrectly.",
    ),
    HelpTopic(
        "Worth knowing",
        "• One currency for the whole app, taken from this device's region. Do not share a " +
            "report between devices set to different currencies — the amounts would be read " +
            "as the wrong money.\n" +
            "• Deleting is immediate and cannot be undone. Back up first.\n" +
            "• A shared file is rewritten in full on every sync, photos included, so a long " +
            "report means a large upload.\n" +
            "• Deleting a whole shared report only removes it here; anyone who still has the " +
            "file can bring it back.",
    ),
)

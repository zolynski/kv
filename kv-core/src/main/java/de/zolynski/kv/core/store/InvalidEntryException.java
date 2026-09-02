package de.zolynski.kv.core.store;

/// Thrown when a key or value breaks one of the rules in [EntryRules].
public class InvalidEntryException extends IllegalArgumentException {

  private final EntryRules.Rule rule;

  public InvalidEntryException(EntryRules.Rule rule, String message) {
    super(message);
    this.rule = rule;
  }

  public EntryRules.Rule rule() {
    return rule;
  }
}

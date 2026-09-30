package zw.co.innbucks.loans.core.workflow;

/** What a role may do at a stage. Working or assigning a stage's items includes seeing its queue. */
public enum Entitlement {
    /** See the stage's queue and its items. */
    VIEW,
    /** Act on the stage's items, and take one for oneself. */
    WORK,
    /** Give the stage's items to others, take them back, and release anyone's. */
    ASSIGN
}

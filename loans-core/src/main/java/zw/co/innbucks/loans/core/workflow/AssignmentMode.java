package zw.co.innbucks.loans.core.workflow;

/** Whether a stage's items are given to a person, and what that means for everyone else. */
public enum AssignmentMode {
    /** Items are not assigned; anyone entitled works them. */
    NONE,
    /** Items can be assigned to route the work; anyone entitled can still act on them. */
    OPTIONAL,
    /** While an item is assigned, only its assignee may act on it; an unassigned one, anyone entitled. */
    EXCLUSIVE
}

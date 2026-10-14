package zw.co.innbucks.loans.core.getmore;

/** A read of one entity (a coupon by id, a sales rule) answered 404 "No such entity.": it does not exist. */
public final class GetMoreNotFoundException extends GetMoreRejectedException {

    public GetMoreNotFoundException(Operation operation, String renderedMessage) {
        super(operation, 404, Reason.NOT_FOUND, renderedMessage);
    }
}

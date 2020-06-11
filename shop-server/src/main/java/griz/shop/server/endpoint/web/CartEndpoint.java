package griz.shop.server.endpoint.web;

import griz.shop.server.domain.Cart;
import griz.shop.server.domain.CartItem;
import griz.shop.server.service.CheckoutService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.http.hateoas.Link;
import io.micronaut.session.Session;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.micronaut.http.HttpResponse.badRequest;
import static io.micronaut.http.HttpResponse.created;
import static io.micronaut.http.MediaType.APPLICATION_JSON;
import static java.lang.String.format;
import static java.util.stream.Collectors.toSet;

/**
 * Endpoint for simulating a shopping cart.
 *
 * <p>Statement management of {@link Cart}s is implemented via an embedded Redis datastore.
 *
 * @author nichollsmc
 */
@Controller("/cart")
public class CartEndpoint {

    private static final String SESSSION_ATTRIBUTE_CART = "shop.cart";

    @Inject
    CheckoutService checkoutService;

    /**
     * Return the current content of the {@link Cart}.
     *
     * @param session the {@link Session} used for managing the state of a {@code Cart}
     * @return the contents of the {@code Cart}
     */
    @Get(produces = APPLICATION_JSON)
    public HttpResponse<?> viewCart(final Session session) {
        return findCart()
                .andThen(HttpResponse::ok)
                .apply(session);
    }

    /**
     * Adds an item to the {@link Cart}.
     *
     * @param httpRequest the {@link HttpRequest} object
     * @param session the {@link Session} used for managing the state of a {@code Cart}
     * @param cartItem the {@code CartItem} to add to the {@code Cart}
     * @return a JSON document representing the updated {@code Cart} content
     */
    @Put(consumes = APPLICATION_JSON, produces = APPLICATION_JSON)
    public HttpResponse<?> addItem(final HttpRequest<?> httpRequest,
                                   final Session session,
                                   @Body @Valid final CartItem cartItem) {
        return handleRequest((cart, existingCartItem) -> {
                    if (existingCartItem.isPresent()) {
                        var errorResponse =
                            new JsonError(format("Item '%s' already exists in cart.", cartItem.getName()))
                                .link(Link.SELF, Link.of(httpRequest.getUri()));

                        return badRequest(errorResponse);
                    }

                    cart.getItems().add(cartItem);
                    session.put(SESSSION_ATTRIBUTE_CART, cart);

                    return created(cart);
                })
                .apply(session, cartItem.getName());
    }

    /**
     * Retrieves a {@link Cart} item using the provided name.
     *
     * @param session the {@link Session} used for managing the state of a {@code Cart}
     * @param name the path parameter representing the name of an item in the {@code Cart}
     * @return the {@code CartItem} for the specified name, otherwise an HTTP 404 - Not found response
     */
    @Get(value = "{name}", produces = APPLICATION_JSON)
    public HttpResponse<CartItem> getItem(final Session session, @NotBlank final String name) {
        return findCart()
                .andThen(findCartItemByName(name))
                .andThen(existingCartItem -> existingCartItem.map(HttpResponse::ok).orElseGet(HttpResponse::notFound))
                .apply(session);
    }

    /**
     * Updates the quantity for an item in the {@link Cart} using the quantity provided in the request body.
     *
     * @param session the {@link Session} used for managing the state of a {@code Cart}
     * @param name the path parameter representing the name of an item in the {@code Cart}
     * @param quantity the new quantity for an item in the {@code Cart} provided in the request body
     * @return a JSON document representing the updated {@code Cart} content
     */
    @Post(value = "{name}", consumes = APPLICATION_JSON, produces = APPLICATION_JSON)
    public HttpResponse<?> updateItemQuantity(final Session session,
                                              @NotBlank final String name,
                                              @Body final Map<String, Long> quantity) {
        return handleRequest((cart, existingCartItem) -> {
                existingCartItem.ifPresent(eci ->
                    Optional.ofNullable(quantity)
                        .flatMap(qmap -> Optional.ofNullable(qmap.get("quantity")).filter(q -> q >= 0))
                        .ifPresent(q -> {
                            if (q == 0) {
                                removeItem(session, name);
                            }

                            eci.setQuantity(BigInteger.valueOf(q));
                            session.put(SESSSION_ATTRIBUTE_CART, cart);
                        }));

                return created(cart);
            })
            .apply(session, name);
    }

    /**
     * Removes an item from the {@link Cart} that matches the provided name.
     *
     * @param session the {@link Session} used for managing the state of a {@code Cart}
     * @param name the path parameter representing the name of an item in the {@code Cart}
     * @return a JSON document representing the updated {@code Cart} content
     */
    @Delete(value = "{name}", produces = APPLICATION_JSON)
    public HttpResponse<?> removeItem(final Session session, @NotBlank final String name) {
        return handleRequest((cart, existingCartItem) -> {
                existingCartItem.ifPresent(eci -> {
                    var updatedItems =
                        cart.getItems().stream().parallel()
                            .filter(i -> !i.getName().equalsIgnoreCase(name))
                            .collect(toSet());

                    cart.setItems(updatedItems);
                    session.put(SESSSION_ATTRIBUTE_CART, cart);
                });

                return created(cart);
            })
            .apply(session, name);
    }

    /**
     * Removes all items from the {@link Cart}.
     *
     * @param session the {@link Session} used for managing the state of a {@code Cart}
     */
    @Post("/clear")
    public void clearCart(final Session session) {
        Optional.ofNullable(session)
            .ifPresent(s -> s.remove(SESSSION_ATTRIBUTE_CART));
    }

    /**
     * Calculates the total (including discounts) of the {@link Cart} and returns a JSON document representing the
     * {@link griz.shop.server.Receipt}.
     *
     * @param session the {@link Session} used for managing the state of a {@code Cart}
     * @return the receipt for the {@code Cart} content
     */
    @Get(value = "/receipt", produces = APPLICATION_JSON)
    public HttpResponse<?> receipt(final Session session) {
        return findCart()
                .andThen(checkoutService.checkout())
                .andThen(HttpResponse::ok)
                .apply(session);
    }

    private BiFunction<Session, String, HttpResponse<?>>
        handleRequest(final BiFunction<Cart, Optional<CartItem>, MutableHttpResponse<?>> cartHandler) {
            return (session, cartItemName) ->
                findCart().andThen(cart ->
                    findCartItemByName(cartItemName)
                        .andThen(existingCartItem -> cartHandler.apply(cart, existingCartItem))
                    .apply(cart))
                .apply(session);
        }


    private Function<Cart, Optional<CartItem>> findCartItemByName(final String name) {
        return cart ->
            cart.getItems().stream()
                .filter(ci -> ci.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    private Function<Session, Cart> findCart() {
        return session ->
            Optional.ofNullable(session)
                .flatMap(s -> s.get(SESSSION_ATTRIBUTE_CART, Cart.class))
                .orElse(Cart.builder().items(new HashSet<>()).build());
    }
}
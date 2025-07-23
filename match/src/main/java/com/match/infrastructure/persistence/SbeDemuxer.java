/*
 * Copyright 2023 Adaptive Financial Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.infrastructure.generated.sbe.CancelOrderDecoder;
import com.match.infrastructure.generated.sbe.CreateOrderDecoder;
import com.match.infrastructure.generated.sbe.MessageHeaderDecoder;
import com.match.infrastructure.generated.sbe.UpdateOrderDecoder;
import org.agrona.DirectBuffer;

import java.math.BigDecimal;

/**
 * Demultiplexes messages from the ingress stream to the appropriate domain handler.
 */
public class SbeDemuxer {
    private final Engine engine;
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    private final CancelOrderDecoder cancelOrderDecoder = new CancelOrderDecoder();
    private final CreateOrderDecoder createOrderDecoder = new CreateOrderDecoder();
    private final UpdateOrderDecoder updateOrderDecoder = new UpdateOrderDecoder();


    /**
     * @param engine match engine
     */
    public SbeDemuxer(
        Engine engine
    ) {
        this.engine = engine;
    }

    /**
     * Dispatch a message to the appropriate domain handler.
     *
     * @param buffer the buffer containing the inbound message, including a header
     * @param offset the offset to apply
     * @param length the length of the message
     */
    public void dispatch(final DirectBuffer buffer, final int offset, final int length) throws Exception {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) {
            System.out.println("Message too short, ignored.");
            return;
        }
        headerDecoder.wrap(buffer, offset);

        switch (headerDecoder.templateId()) {
            case CancelOrderDecoder.TEMPLATE_ID:
            {
                cancelOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

                CancelOrderCommand cancel = new CancelOrderCommand();

                cancel.setUserId(cancelOrderDecoder.userId());
                cancel.setOrderId(cancelOrderDecoder.orderId());

                engine.acceptOrder(
                        cancelOrderDecoder.market(),
                        "cancel",
                        cancel

                );
                break;
            }
            case CreateOrderDecoder.TEMPLATE_ID: {
                createOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

                CreateOrderCommand create = new CreateOrderCommand();
                create.setUserId(createOrderDecoder.userId());
                create.setPrice(BigDecimal.valueOf(createOrderDecoder.price()));
                create.setQuantity(BigDecimal.valueOf(createOrderDecoder.quantity()));
                create.setTotalPrice(BigDecimal.valueOf(createOrderDecoder.totalPrice()));
                create.setOrderSide(toDomainOrderSide(createOrderDecoder.orderSide()));
                create.setOrderType(toDomainOrderType(createOrderDecoder.orderType()));

                engine.acceptOrder(
                        createOrderDecoder.market(),
                        "create",
                        create
                );
                break;
            }
            case UpdateOrderDecoder.TEMPLATE_ID: {
                updateOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                UpdateOrderCommand update = new UpdateOrderCommand();
                update.setUserId(updateOrderDecoder.userId());
                update.setOrderId(updateOrderDecoder.orderId());
                update.setPrice(BigDecimal.valueOf(updateOrderDecoder.price()));
                update.setQuantity(BigDecimal.valueOf(updateOrderDecoder.quantity()));
                update.setOrderSide(toDomainOrderSide(updateOrderDecoder.orderSide()));
                update.setOrderType(toDomainOrderType(updateOrderDecoder.orderType()));

                engine.acceptOrder(
                        updateOrderDecoder.market(),
                        "update",
                        update
                );
                break;
            }
            default:
                System.out.printf("Unknown message template %s, ignored.", headerDecoder.templateId());
        }
    }

    private com.match.domain.enums.OrderType toDomainOrderType(com.match.infrastructure.generated.sbe.OrderType sbeOrderType) {
        switch (sbeOrderType) {
            case LIMIT:
                return com.match.domain.enums.OrderType.LIMIT;
            case MARKET:
                return com.match.domain.enums.OrderType.MARKET;
            case LIMIT_MAKER:
                return com.match.domain.enums.OrderType.LIMIT_MAKER;
            default:
                throw new IllegalArgumentException("Unsupported OrderType: " + sbeOrderType);
        }
    }

    private com.match.domain.enums.OrderSide toDomainOrderSide(com.match.infrastructure.generated.sbe.OrderSide sbeOrderSide) {
        switch (sbeOrderSide) {
            case BID:
                return com.match.domain.enums.OrderSide.BID;
            case ASK:
                return com.match.domain.enums.OrderSide.ASK;
            default:
                throw new IllegalArgumentException("Unsupported OrderSide: " + sbeOrderSide);
        }
    }
}

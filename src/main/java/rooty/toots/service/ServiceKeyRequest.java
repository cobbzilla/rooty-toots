package rooty.toots.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import rooty.RootyMessage;

@Accessors(chain=true)
@NoArgsConstructor @AllArgsConstructor
public class ServiceKeyRequest extends RootyMessage {

    public enum Operation {
        GENERATE, DESTROY, ALLOW_SSH;
        @JsonCreator public static Operation create(String op) { return valueOf(op.toUpperCase()); }
    }

    public enum Recipient {
        VENDOR, CUSTOMER;
        @JsonCreator public static Recipient create(String op) { return valueOf(op.toUpperCase()); }
    }

    // for ALLOW_SSH requests
    public ServiceKeyRequest (Operation op) { this.operation = op; }

    @Getter @Setter private String name;
    @Getter @Setter private Operation operation;
    @Getter @Setter private Recipient recipient;

}

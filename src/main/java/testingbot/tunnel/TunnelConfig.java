package testingbot.tunnel;

import java.io.Serializable;
import org.kohsuke.stapler.DataBoundConstructor;

public class TunnelConfig implements Serializable {
    private String tunnelOptions;
    
    public TunnelConfig() {
    }
    
    @DataBoundConstructor
    public TunnelConfig(String tunnelOptions) {
        this.tunnelOptions = tunnelOptions;
    }

    /**
     * @return the tunnelOptions
     */
    public String getTunnelOptions() {
        return tunnelOptions;
    }

    /**
     * @param tunnelOptions the tunnelOptions to set
     */
    public void setTunnelOptions(String tunnelOptions) {
        this.tunnelOptions = tunnelOptions;
    }
}

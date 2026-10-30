package com.finalysis.ai.selfhost;

import software.amazon.awssdk.services.ec2.Ec2Client;

/**
 * EC2 clients per region for the Self-hosted controls. Tests give {@link SelfHostControl} a fake; the app's are
 * {@link AwsEc2Clients}, which exist only with Cloud credentials (the {@code aws} profile).
 */
interface Ec2Clients {

    /** Whether there are Cloud credentials to sign EC2 calls with. */
    boolean available();

    /** A client for this region; only when {@link #available()}. */
    Ec2Client client(String region);
}

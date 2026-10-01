package examples;

import io.genesis.sandbox.Client;

import java.nio.charset.StandardCharsets;

import static io.genesis.sandbox.SandboxModels.*;

/** Synchronous, strongly typed one-shot Job + Artifact example. */
public final class JobArtifactSync {
    public static void main(String[] args) throws Exception {
        Client client = new Client(
                getenv("GENESIS_SANDBOX_BASE_URL", "http://127.0.0.1:18010"),
                getenv("GENESIS_SANDBOX_API_KEY", "test-token-1")
        );
        demoOutputArtifact(client);
        demoInputArtifact(client);
    }

    private static void demoOutputArtifact(Client client) throws Exception {
        SubmitJobRequest req = new SubmitJobRequest()
                .environment(EnvironmentSelector.profile("code-polyglot-basic"))
                .code("import os\n"
                        + "os.makedirs('/workspace/output', exist_ok=True)\n"
                        + "with open('/workspace/output/hello.txt', 'w', encoding='utf-8') as f:\n"
                        + "    f.write('Hello from Java Sandbox output artifact.')\n"
                        + "print('artifact generated')");
        JobResult job = submitAndWait(client, req);

        System.out.println("ExitCode: " + job.exitCode);
        System.out.println("Stdout: " + job.stdout);
        System.out.println("Stderr: " + job.stderr);
        if (job.outputArtifacts.isEmpty()) {
            throw new IllegalStateException("job completed without an output artifact");
        }
        Artifact artifact = job.outputArtifacts.get(0);
        byte[] content = client.downloadArtifact(artifact.artifactId);
        System.out.printf("artifact=%s (%s)%n%s%n",
                artifact.artifactId, artifact.name,
                new String(content, StandardCharsets.UTF_8));
    }

    private static void demoInputArtifact(Client client) throws Exception {
        JobResult first = submitAndWait(client, new SubmitJobRequest()
                .environment(EnvironmentSelector.profile("code-polyglot-basic"))
                .code("print('step 1 execution completed')"));

        Artifact input = client.uploadJobFile(
                first.jobId,
                "data.csv",
                "key,value\nitem1,100\nitem2,200\n".getBytes(StandardCharsets.UTF_8)
        );
        JobResult second = submitAndWait(client, new SubmitJobRequest()
                .environment(EnvironmentSelector.profile("code-polyglot-basic"))
                .inputArtifact(input.artifactId)
                .code("from pathlib import Path\n"
                        + "input_file = Path('/workspace/input/data.csv')\n"
                        + "print(input_file.read_text())"));
        System.out.print(second.stdout);
    }

    private static JobResult submitAndWait(Client client, SubmitJobRequest request)
            throws Exception {
        JobResult submitted = client.submitJob(request);
        return client.waitJob(submitted.jobId, 200L);
    }

    private static String getenv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

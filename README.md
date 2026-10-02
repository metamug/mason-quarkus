# Mason for Quarkus (MQ)

MQ runs [R2](https://github.com/metamug/R2) resource XML directly on [Quarkus](https://quarkus.io): the XML is parsed once
(StAX), held as an in-memory model, and executed by plain Java executors, replacing the XML to JSP to Tomcat translation layer
of the original Mason. MQ is built on Quarkus (Apache 2.0); it is not affiliated with or endorsed by the Quarkus project.

**Status: spikes.** Nothing here is the product yet. `spikes/` holds throwaway code written to answer risky questions with
measurements before anything is built on them (see `spikes/README.md`).

## Licence

Apache License 2.0, see `LICENSE`.

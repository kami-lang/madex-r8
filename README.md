# Madex's Backend
This is a fork of the **D8** dexer and **R8** shrinker used internally by [**Madex**](https://github.com/kami-lang/madex).

This repository is basically just a specially customized version of **Madex**, **which is not the source of truth**. So we do not support or recommend "**directly**" using the code of this repository, if you **really need**, please check out the upstream repository: https://r8.googlesource.com/r8

> We will only consider issues and PRs related to **Madex** in this repo. All other PRs and issues should be sent to [the upstream repository.](https://r8.googlesource.com/r8)

[//]: # (## Diff)

[//]: # ()
[//]: # (```diff)

[//]: # (- new DexType&#40;&#41;)

[//]: # (+ TypeImpl&#40;&#41;)

[//]: # ()
[//]: # (- DexType.getSimpleName)

[//]: # (+ DexType.simpleName)

[//]: # ()
[//]: # (- DexType.getPackageName)

[//]: # (+ DexType.packageName)

[//]: # ()
[//]: # (- DexType.descriptor.toString&#40;&#41;)

[//]: # (- DexType.toDescriptorString&#40;&#41;)

[//]: # (+ DexType.descriptorString)

[//]: # ()
[//]: # (```)

[//]: # ()
[//]: # (```diff)

[//]: # (- DexParser.readFields - DexEncodedField.builder&#40;&#41;)

[//]: # (+ PropertyImpl&#40;&#41;)

[//]: # ()
[//]: # (- DexEncodedField.getDeprecated)

[//]: # (+ DexEncodedField.deprecated)

[//]: # (```)

## License

See [LICENSE](LICENSE)
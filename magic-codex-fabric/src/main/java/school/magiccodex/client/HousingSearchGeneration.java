package school.magiccodex.client;
final class HousingSearchGeneration{private long serial;long next(){return ++serial;}boolean accepts(long generation){return generation==serial;}private HousingSearchGeneration(boolean unused){}HousingSearchGeneration(){}}

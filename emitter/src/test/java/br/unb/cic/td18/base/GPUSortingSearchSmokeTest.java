package br.unb.cic.td18.base;

import br.unb.cic.td18.permutation.OneLinePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "td18.gpuSmoke", matches = "true")
class GPUSortingSearchSmokeTest {

    @Test
    void sortsConcurrentThreeCyclesThroughTheBoundedDispatcher() throws Exception {
        GPUSortingSearch.init(new int[] { 0 }, 2, 32L * 1024L * 1024L);

        val executor = Executors.newVirtualThreadPerTaskExecutor();
        val futures = new ArrayList<Future<?>>();

        try {
            for (var search = 0; search < 32; search++) {
                futures.add(executor.submit(() -> {
                    val moves = GPUSortingSearch.getInstance().search(new byte[] { 0, 1, 2 },
                            new OneLinePermutation(new byte[] { 1, 2, 0 }),
                            1,
                            false);

                    assertEquals(GPUSortingSearch.Disposition.COMPLETE, moves.disposition());
                    assertEquals(1, moves.moves().size());
                    assertArrayEquals(new int[] { 0, 1, 2 }, moves.moves().getFirst());
                }));
            }
            for (val future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}

import { createRouter, createWebHashHistory } from "vue-router";
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/:tab(worlds|models|scenarios|studio)?",
      component: { render: () => null },
    },
  ],
});
